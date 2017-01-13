/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.aliyun.maxcompute.datasource

import java.io.EOFException

import com.aliyun.odps.tunnel.TableTunnel
import com.aliyun.odps.tunnel.io.TunnelRecordReader
import com.aliyun.odps.{PartitionSpec, Odps}
import com.aliyun.odps.account.AliyunAccount
import org.apache.spark.aliyun.odps.OdpsPartition
import org.apache.spark.sql.catalyst.expressions.SpecificMutableRow
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.NextIterator
import org.apache.spark.{InterruptibleIterator, TaskContext, Partition, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types._

import scala.collection.mutable.ArrayBuffer

/**
 * Created by songjun on 16/12/20.
 */

class ODPSRDD(
  sc: SparkContext,
  schema: StructType,
  accessKeyId: String,
  accessKeySecret: String,
  odpsUrl: String,
  tunnelUrl: String,
  project: String,
  table: String,
  partitionSpec: String,
  numPartitions: Int)
  extends RDD[InternalRow](sc, Nil) {

  /** Implemented by subclasses to compute a given partition. */
  override def compute(theSplit: Partition, context: TaskContext): Iterator[InternalRow] = {
    val iter = new NextIterator[InternalRow] {
      val split = theSplit.asInstanceOf[OdpsPartition]

      val account = new AliyunAccount(accessKeyId, accessKeySecret)
      val odps = new Odps(account)
      odps.setDefaultProject(project)
      odps.setEndpoint(odpsUrl)
      val tunnel = new TableTunnel(odps)
      tunnel.setEndpoint(tunnelUrl)
      var downloadSession: TableTunnel#DownloadSession = null
      if(partitionSpec.equals("Non-Partitioned"))
        downloadSession = tunnel.createDownloadSession(project, table)
      else {
        val parSpec = new PartitionSpec(partitionSpec)
        downloadSession = tunnel.createDownloadSession(project, table, parSpec)
      }
      val reader = downloadSession.openRecordReader(split.start, split.count)
      val inputMetrics = context.taskMetrics.inputMetrics

      context.addTaskCompletionListener {
        context => closeIfNeeded()
      }

      val mutableRow = new SpecificMutableRow(schema.fields.map(x => x.dataType))
      override def getNext(): InternalRow = {

        try {
          val r = reader.read()
          if (r != null) {
            schema.zipWithIndex.foreach{
              case (s: StructField, idx: Int) =>
               s.dataType match {
                 case BooleanType => mutableRow.setBoolean(idx, r.getBoolean(s.name))
                 case DoubleType => mutableRow.setDouble(idx, r.getDouble(s.name))
                 case LongType => mutableRow.setLong(idx, r.getBigint(s.name))
                 case StringType => mutableRow.update(idx, UTF8String.fromString(r.getString(s.name)))
                 case TimestampType =>  mutableRow.setLong(idx, r.getDatetime(s.name).getTime)
               }
            }
            inputMetrics.incRecordsRead(1L)
            mutableRow
          } else {
            finished = true
            null.asInstanceOf[InternalRow]
          }
        } catch {
          case eof: EOFException =>
            finished = true
            null.asInstanceOf[InternalRow]
        }
      }

      override def close() {
        try {
          val totalBytes = reader.asInstanceOf[TunnelRecordReader].getTotalBytes
          inputMetrics.incBytesRead(totalBytes)
          reader.close()
        } catch {
          case e: Exception => logWarning("Exception in RecordReader.close()", e)
        }
      }
    }

    new InterruptibleIterator[InternalRow](context, iter)
  }

  /**
   * Implemented by subclasses to return the set of partitions in this RDD. This method will only
   * be called once, so it is safe to implement a time-consuming computation in it.
   */
  override def getPartitions: Array[Partition] = {
    var ret = null.asInstanceOf[Array[Partition]]

    val account = new AliyunAccount(accessKeyId, accessKeySecret)
    val odps = new Odps(account)
    odps.setDefaultProject(project)
    odps.setEndpoint(odpsUrl)
    val tunnel = new TableTunnel(odps)
    tunnel.setEndpoint(tunnelUrl)
    var downloadSession: TableTunnel#DownloadSession = null
    if(partitionSpec == null || partitionSpec.equals("Non-Partitioned"))
      downloadSession = tunnel.createDownloadSession(project, table)
    else {
      val parSpec = new PartitionSpec(partitionSpec)
      downloadSession = tunnel.createDownloadSession(project, table, parSpec)
    }
    val downloadCount = downloadSession.getRecordCount
    logDebug("Odps project " + project + " table " + table + " with partition "
      + partitionSpec + " contain " + downloadCount + " line data.")
    var numPartition_ = math.min(math.max(1, numPartitions),
      if (downloadCount > Int.MaxValue) Int.MaxValue else downloadCount.toInt)
    if(numPartition_ == 0) {
      numPartition_ = 1
      logDebug("OdpsRdd has one partition at least.")
    }
    val range = getRanges(downloadCount, 0, numPartition_)
    ret = Array.tabulate(numPartition_) {
      idx =>
        val (start, end) = range(idx)
        val count = (end - start + 1).toInt
        new OdpsPartition(
          this.id,
          idx,
          start,
          count,
          accessKeyId,
          accessKeySecret,
          odpsUrl,
          tunnelUrl,
          project,
          table,
          partitionSpec
        )
    }.filter(p => p.count > 0) //remove the last count==0 to prevent exceptions from reading odps table.
      .map(_.asInstanceOf[Partition])
    ret
  }

  def getRanges(max: Long, min: Long, numRanges: Int): Array[(Long, Long)] = {
    val span = max - min + 1
    val initSize = span / numRanges
    val sizes = Array.fill(numRanges)(initSize)
    val remainder = span - numRanges * initSize
    for (i <- 0 until remainder.toInt) {
      sizes(i) += 1
    }
    assert(sizes.reduce(_ + _) == span)
    val ranges = ArrayBuffer.empty[(Long, Long)]
    var start = min
    sizes.filter(_ > 0).foreach { size =>
      val end = start + size - 1
      ranges += Tuple2(start, end)
      start = end + 1
    }
    assert(start == max + 1)
    ranges.toArray
  }


  override def checkpoint() {
    // Do nothing. ODPS RDD should not be checkpointed.
  }

}
