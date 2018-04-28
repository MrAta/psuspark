/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.rdd

import java.io._

import scala.Serializable
import scala.collection.Map
import scala.collection.immutable.NumericRange
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.util.Sorting

import org.apache.spark._
import org.apache.spark.serializer.JavaSerializer
import org.apache.spark.util.Utils

private[spark] class ParallelCollectionPartition[T: ClassTag](
    var rddId: Long,
    var slice: Int,
    var values: Seq[T]
  ) extends Partition with Serializable {

  def iterator: Iterator[T] = values.iterator

  override def hashCode(): Int = (41 * (41 + rddId) + slice).toInt

  override def equals(other: Any): Boolean = other match {
    case that: ParallelCollectionPartition[_] =>
      this.rddId == that.rddId && this.slice == that.slice
    case _ => false
  }

  override def index: Int = slice

  @throws(classOf[IOException])
  private def writeObject(out: ObjectOutputStream): Unit = Utils.tryOrIOException {

    val sfactory = SparkEnv.get.serializer

    // Treat java serializer with default action rather than going thru serialization, to avoid a
    // separate serialization header.

    sfactory match {
      case js: JavaSerializer => out.defaultWriteObject()
      case _ =>
        out.writeLong(rddId)
        out.writeInt(slice)

        val ser = sfactory.newInstance()
        Utils.serializeViaNestedStream(out, ser)(_.writeObject(values))
    }
  }

  @throws(classOf[IOException])
  private def readObject(in: ObjectInputStream): Unit = Utils.tryOrIOException {

    val sfactory = SparkEnv.get.serializer
    sfactory match {
      case js: JavaSerializer => in.defaultReadObject()
      case _ =>
        rddId = in.readLong()
        slice = in.readInt()

        val ser = sfactory.newInstance()
        Utils.deserializeViaNestedStream(in, ser)(ds => values = ds.readObject[Seq[T]]())
    }
  }
}

private[spark] class ParallelCollectionRDD[T: ClassTag](
    @transient private val sc: SparkContext,
    @transient private val data: Seq[T],
    numSlices: Int,
    locationPrefs: Map[Int, Seq[String]])
    extends RDD[T](sc, Nil) {
  // a flag indicating whether optRepartition() has been called, it might be used in
  // getPreferedLocations
  private var opted = false

  // optimized prefered locations
  private var optLocationPrefs = locationPrefs

  // Identify locations of executors with this prefix.
  val executorLocationTag = "executor_"

  // TODO: Right now, each split sends along its full data, even if later down the RDD chain it gets
  // cached. It might be worthwhile to write the data to a file in the DFS and read it in the split
  // instead.
  // UPDATE: A parallel collection can be checkpointed to HDFS, which achieves this goal.

  override def getPartitions: Array[Partition] = {
    var slices: Array[Seq[T]] = null
    if (!opted) {
      slices = ParallelCollectionRDD.slice(data, numSlices).toArray
    } else {
      slices = ParallelCollectionRDD.slice(data, sc).toArray
      // Update preferred location after the optimized partition
      updatePrefLoc()
    }
    slices.indices.map(i => new ParallelCollectionPartition(id, i, slices(i))).toArray
  }

  // TODO(ata and nader): override the function optRepartition to change the behavior of
  // getPartitions (re-slice the cake) according to the computation ability of the executors,
  // which should have been stored in sc.executorTokens. Also, after calling this function,
  // the behavior of getPreferredLocations should be changed. See line 65-69 in TaskLocation.scala
  // for valid location format. I think executor_[hostname]_[executorid] is a good choice if we use
  // executor -> num of tokens mapping, or [host] is enough if we use host -> num of tokens
  // mapping.
  override def optRepartition(): Unit = {
    // TODO(ata): change the behavior of getPartitions(), maybe set var opted to true, and add
    // if(opted) {} clause so that it can call a different overloaded
    // ParallelCollectionRDD.slice()?
    opted = true
  }

  private def updatePrefLoc(): Unit = {
    // TODO(nader): don't forget to update locationPrefs, using sc.executorTokens
    // (and maybe sc.executorToHost).

    // create an ArrayList of class ExecutorPair
    case class ExecutorPair(val executorId: String, val tokens: Int)
    var availableArray = Array[ExecutorPair]()
    for (exeID <- sc.executorTokens.keySet().toArray()) {
      val exeIDasString = exeID.asInstanceOf[String]
      availableArray = availableArray :+ ExecutorPair(
        exeIDasString, sc.executorTokens.get(exeIDasString)._1)
    }

    // sort availabeArray in ascending order
    object PairOrdering extends Ordering[ExecutorPair] {
      def compare(a: ExecutorPair, b: ExecutorPair) = a.tokens compare b.tokens
    }
    Sorting.quickSort(availableArray)(PairOrdering)

    // after the array is sorted, assign each partition to an availabe executor
    for ((pair, partID) <- availableArray.zipWithIndex) {
      val execID = pair.executorId
      val execHost = executorLocationTag + s"${sc.executorToHost.get(execID)}_$execID"
      if (optLocationPrefs.contains(partID)) {
        optLocationPrefs = optLocationPrefs.updated(
          partID, optLocationPrefs(partID) :+ execHost)
      } else {
        optLocationPrefs += (partID -> Seq(execHost))
      }
    }

  }

  override def compute(s: Partition, context: TaskContext): Iterator[T] = {
    new InterruptibleIterator(context, s.asInstanceOf[ParallelCollectionPartition[T]].iterator)
  }

  override def getPreferredLocations(s: Partition): Seq[String] = {
    optLocationPrefs.getOrElse(s.index, Nil)
  }
}

private object ParallelCollectionRDD {
  /**
   * Slice a collection into numSlices sub-collections. One extra thing we do here is to treat Range
   * collections specially, encoding the slices as other Ranges to minimize memory cost. This makes
   * it efficient to run Spark over RDDs representing large sets of numbers. And if the collection
   * is an inclusive Range, we use inclusive range for the last slice.
   */
  def slice[T: ClassTag](seq: Seq[T], numSlices: Int): Seq[Seq[T]] = {
    if (numSlices < 1) {
      throw new IllegalArgumentException("Positive number of partitions required")
    }
    // Sequences need to be sliced at the same set of index positions for operations
    // like RDD.zip() to behave as expected
    def positions(length: Long, numSlices: Int): Iterator[(Int, Int)] = {
      (0 until numSlices).iterator.map { i =>
        val start = ((i * length) / numSlices).toInt
        val end = (((i + 1) * length) / numSlices).toInt
        (start, end)
      }
    }
    seq match {
      case r: Range =>
        positions(r.length, numSlices).zipWithIndex.map { case ((start, end), index) =>
          // If the range is inclusive, use inclusive range for the last slice
          if (r.isInclusive && index == numSlices - 1) {
            new Range.Inclusive(r.start + start * r.step, r.end, r.step)
          }
          else {
            new Range(r.start + start * r.step, r.start + end * r.step, r.step)
          }
        }.toSeq.asInstanceOf[Seq[Seq[T]]]
      case nr: NumericRange[_] =>
        // For ranges of Long, Double, BigInteger, etc
        val slices = new ArrayBuffer[Seq[T]](numSlices)
        var r = nr
        for ((start, end) <- positions(nr.length, numSlices)) {
          val sliceSize = end - start
          slices += r.take(sliceSize).asInstanceOf[Seq[T]]
          r = r.drop(sliceSize)
        }
        slices
      case _ =>
        val array = seq.toArray // To prevent O(n^2) operations for List etc
        positions(array.length, numSlices).map { case (start, end) =>
            array.slice(start, end).toSeq
        }.toSeq
    }
  }

  def slice[T: ClassTag](seq: Seq[T], sc: SparkContext): Seq[Seq[T]] = {
    val executorTokens = sc.executorTokens
    // Here we do the partition based on the values in executorTokens.

    if (executorTokens.size() < 1) {
      throw new IllegalArgumentException("Positive number of partitions required")
    }
    // Sequences need to be sliced at the same set of index positions for operations
    // like RDD.zip() to behave as expected
    val tokens = executorTokens.values().toArray.map {i =>
      val tmp = i.asInstanceOf[(Int, String)]._1
      if (i.asInstanceOf[(Int, String)]._1 - 15 > 0) {
        (i.asInstanceOf[(Int, String)]._1 - 15, i.asInstanceOf[(Int, String)]._2)
      }
      else
        {
          (0, i.asInstanceOf[(Int, String)]._2)
        }


      }.sorted

    val numSlices = executorTokens.values().size()
    val pi = numSlices // Total amount of work done by single node single vCPU
    // baseline performance of vCPU
    // TODO(yuquanshan): So far the baseline performance is hardcoded and only true
    // for a certain AWS instance (t2.medium) and need to change if using other types
    // of instances. So we need to let our code to automatically detect instance type
    // and adaptively change the baseline performance.
    // val bf = 0.4

    def bf(instanceType: String): Double = {
      instanceType match {
        case "t2.nano" => 0.05
        case "t2.micro" => 0.1
        case "t2.small" => 0.2
        case "t2.medium" => 0.4
        case "t2.large" => 0.6
        case "t2.xlarge" => 0.9
        case _ => 1.0
      }
    }

    // maximum available performance!
    // see https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/t2-credits-baseline-concepts.html

    def mf(instanceType: String): Double = {
      instanceType match {
        case "t2.nano" => 1.0
        case "t2.micro" => 1.0
        case "t2.small" => 1.0
        case "t2.medium" => 2.0
        case "t2.large" => 2.0
        case "t2.xlarge" => 4.0
        case _ => 1.0
      }
    }
    def solvePieceWise(start: Int, passover: Double, tango: Double): Double = {
      var slope: Double = 0.0
        tokens.foreach(i =>
          if (i._1 <= start)
          {
            slope = slope + bf(i._2)
          }
          else {
            slope = slope + mf(i._2)
          }
        )
      val newIndex = tokens.count(_._1 <= start)
      if (newIndex == tokens.length) {
        (tango - passover) / slope + start
      } else {
        val newPassover = slope * (tokens(newIndex)._1 - start) + passover
        if (newPassover >= tango) {
          (tango - passover) / slope + start
        } else {
          solvePieceWise(tokens(newIndex)._1, newPassover, tango)
        }
      }
    }

    val finTime = solvePieceWise(0, 0.0, pi)

    val weights = tokens.map { i =>
      if (i._1 > finTime) {
        finTime.asInstanceOf[Double]
      } else {
        i._1 + (finTime - i._1) * bf(i._2)
      }}.toArray.map(_.asInstanceOf[Double])

    def positions(length: Long, ws: Array[Double]): Iterator[(Int, Int)] = {
      var start = 0
      var end = 0
      var offset = 0
      (0 until numSlices).iterator.map { i =>
        start = offset
        end = (start + (ws(i) * length) / ws.sum).toInt
        offset = end
        (start, end)
      }
    }
    seq match {
      case r: Range =>
        positions(r.length, weights).zipWithIndex.map { case ((start, end), index) =>
          // If the range is inclusive, use inclusive range for the last slice
          if (r.isInclusive && index == numSlices - 1) {
            new Range.Inclusive(r.start + start * r.step, r.end, r.step)
          }
          else {
            new Range(r.start + start * r.step, r.start + end * r.step, r.step)
          }
        }.toSeq.asInstanceOf[Seq[Seq[T]]]

      case nr: NumericRange[_] =>
        // For ranges of Long, Double, BigInteger, etc
        val slices = new ArrayBuffer[Seq[T]](numSlices)
        var r = nr
        for ((start, end) <- positions(nr.length, weights)) {
          val sliceSize = end - start
          slices += r.take(sliceSize).asInstanceOf[Seq[T]]
          r = r.drop(sliceSize)
        }
        slices
      case _ =>
        val array = seq.toArray // To prevent O(n^2) operations for List etc
        positions(array.length, weights).map { case (start, end) =>
          array.slice(start, end).toSeq
        }.toSeq
    }
  }
}
