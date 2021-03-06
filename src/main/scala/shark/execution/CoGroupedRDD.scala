/*
 * Copyright (C) 2012 The Regents of The University California. 
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spark

import java.io.{ObjectOutputStream, IOException}
import java.util.{HashMap => JHashMap}

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

// A version of CoGroupedRDD with the following changes:
// - Disable map-side aggregation.
// - Enforce return type to Array[ArrayBuffer].

sealed trait CoGroupSplitDep extends Serializable

case class NarrowCoGroupSplitDep(
    rdd: RDD[_],
    splitIndex: Int,
    var split: Split
  ) extends CoGroupSplitDep {

  @throws(classOf[IOException])
  private def writeObject(oos: ObjectOutputStream) {
    // Update the reference to parent split at the time of task serialization
    split = rdd.splits(splitIndex)
    oos.defaultWriteObject()
  }
}

case class ShuffleCoGroupSplitDep(shuffleId: Int) extends CoGroupSplitDep

class CoGroupSplit(idx: Int, val deps: Seq[CoGroupSplitDep]) extends Split with Serializable {
  override val index: Int = idx
  override def hashCode(): Int = idx
}

class CoGroupAggregator
  extends Aggregator[Any, Any, ArrayBuffer[Any]](
    { x => ArrayBuffer(x) },
    { (b, x) => b += x },
    null)
  with Serializable

// Disable map-side combine during aggregation.
class CoGroupedRDD[K](@transient var rdds: Seq[RDD[(_, _)]], part: Partitioner)
  extends RDD[(K, Array[ArrayBuffer[Any]])](rdds.head.context, Nil) with Logging {

  val aggr = new CoGroupAggregator

  @transient
  var deps_ = {
    val deps = new ArrayBuffer[Dependency[_]]
    for ((rdd, index) <- rdds.zipWithIndex) {
      if (rdd.partitioner == Some(part)) {
        logInfo("Adding one-to-one dependency with " + rdd)
        deps += new OneToOneDependency(rdd)
      } else {
        logInfo("Adding shuffle dependency with " + rdd)
        deps += new ShuffleDependency[Any, Any](rdd, part)
      }
    }
    deps.toList
  }

  override def getDependencies = deps_
  @transient
  var splits_ : Array[Split] = {
    val firstRdd = rdds.head
    val array = new Array[Split](part.numPartitions)
    for (i <- 0 until array.size) {
      array(i) = new CoGroupSplit(i, rdds.zipWithIndex.map { case (r, j) =>
        dependencies(j) match {
          case s: ShuffleDependency[_, _] =>
            new ShuffleCoGroupSplitDep(s.shuffleId): CoGroupSplitDep
          case _ =>
            new NarrowCoGroupSplitDep(r, i, r.splits(i)): CoGroupSplitDep
        }
      }.toList)
    }
    array
  }

  override def getSplits = splits_

  override val partitioner = Some(part)

  override def compute(s: Split, context: TaskContext): Iterator[(K, Array[ArrayBuffer[Any]])] = {
    val split = s.asInstanceOf[CoGroupSplit]
    val numRdds = split.deps.size
    val map = new JHashMap[K, Array[ArrayBuffer[Any]]]
    def getSeq(k: K): Array[ArrayBuffer[Any]] = {
      var values = map.get(k)
      if (values == null) {
        values = Array.fill(numRdds)(new ArrayBuffer[Any])
        map.put(k, values)
      }
      values
    }
    for ((dep, depNum) <- split.deps.zipWithIndex) dep match {
      case NarrowCoGroupSplitDep(rdd, itsSplitIndex, itsSplit) => {
        // Read them from the parent
        for ((k, v) <- rdd.iterator(itsSplit, context)) { getSeq(k.asInstanceOf[K])(depNum) += v }
      }
      case ShuffleCoGroupSplitDep(shuffleId) => {
        // Read map outputs of shuffle
        def mergePair(pair: (K, Any)) { getSeq(pair._1)(depNum) += pair._2 }
        val fetcher = SparkEnv.get.shuffleFetcher
        fetcher.fetch[K, Seq[Any]](shuffleId, split.index).foreach(mergePair)
      }
    }
    map.iterator
  }
  
  override def clearDependencies() {
    deps_ = null
    splits_ = null
    rdds = null
  }
}
