package com.flink.common.kafka

import java.{util => ju}

import com.alibaba.fastjson.JSON
import org.apache.kafka.clients.consumer.{
  KafkaConsumer,
  OffsetAndMetadata,
  OffsetAndTimestamp
}
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

object KafkaUtil {
  lazy val _log = LoggerFactory.getLogger(KafkaUtil.getClass)
  var kafkaUtil: KafkaUtil = null
  def apply(kafkaParams: ju.Map[String, Object]): KafkaUtil = {
    if (kafkaUtil == null) {
      kafkaUtil = new KafkaUtil(kafkaParams)
    }
    kafkaUtil
  }
  class KafkaUtil(kafkaParams: java.util.Map[String, Object]) {
    lazy val comsumer = createConsumer
    private def createConsumer: KafkaConsumer[String, String] = {
      val c = new KafkaConsumer[String, String](kafkaParams)
      c
    }

    /**
      * 按照时间戳获取对于的offset
      * @param topics
      * @param timeStamp
      * @return
      */
    def offsetsForTimes(topics: Set[String], timeStamp: java.lang.Long)
      : java.util.Map[TopicPartition, OffsetAndTimestamp] = {
      import scala.collection.JavaConverters._
      val parts = topics.flatMap(tp => { comsumer.partitionsFor(tp).asScala })
      val m = parts
        .map(x => {
          (new TopicPartition(x.topic(), x.partition()), timeStamp)
        })
        .toMap
      comsumer.offsetsForTimes(m.asJava)
    }

    /**
      *获取最新得offset
      * @param topics
      */
    def getLastestOffset(topics: Set[String]): Map[TopicPartition, Long] = {
      comsumer.subscribe(topics.asJava)
      comsumer.poll(0)
      val parts = comsumer.assignment()
      comsumer
        .endOffsets(parts)
        .asScala
        .map { case (tp, l) => (tp -> l.toLong) }
        .toMap
//      val currentOffset = parts.asScala.map { tp =>
//        tp -> comsumer.position(tp)
//      }.toMap
//      comsumer.pause(parts)
//      comsumer.seekToEnd(parts)
//      val re = parts.asScala.map { ps =>
//        ps -> comsumer.position(ps)
//      }
//      currentOffset.foreach { case (tp, l) => comsumer.seek(tp, l) }
//      re.toMap
    }

    /**
      * @author LMQ
      * @time 2018-10-31
      * @desc 获取最开始偏移量
      */
    def getEarleastOffset(topics: Set[String]): Map[TopicPartition, Long] = {
      comsumer.subscribe(topics.asJava)
      comsumer.poll(0)
      val parts = comsumer.assignment()
      comsumer
        .beginningOffsets(parts)
        .asScala
        .map { case (tp, l) => (tp -> l.toLong) }
        .toMap
//      val currentOffset = parts.asScala.map { tp =>
//        tp -> comsumer.position(tp)
//      }.toMap
//      comsumer.pause(parts)
//      comsumer.seekToBeginning(parts)
//      val re = parts.asScala.map { ps =>
//        ps -> comsumer.position(ps)
//      }
//      currentOffset.foreach { case (tp, l) => comsumer.seek(tp, l) }
//      re.toMap
    }

    /**
      *
      * @param offset
      */
    def commitOffset(offset: Map[TopicPartition, Long]): Unit = {
      import scala.collection.JavaConverters._
      val map = offset.map {
        case (tp, offset) =>
          tp -> new OffsetAndMetadata(offset)
      }.asJava

      comsumer.commitSync(map)
    }

    /**
      *
      * @return
      */
    def getBrokers(topics: Set[String]): ju.HashMap[TopicPartition, String] = {
      val result = new ju.HashMap[TopicPartition, String]()
      val hosts = new ju.HashMap[TopicPartition, String]()
      comsumer.subscribe(topics.asJava)
      comsumer.poll(0)
      val assignments = comsumer.assignment().iterator()
      while (assignments.hasNext()) {
        val tp: TopicPartition = assignments.next()
        if (null == hosts.get(tp)) {
          val infos = comsumer.partitionsFor(tp.topic).iterator()
          while (infos.hasNext()) {
            val i = infos.next()
            hosts.put(new TopicPartition(i.topic(), i.partition()),
                      i.leader.host())
          }
        }
        result.put(tp, hosts.get(tp))
      }
      result
    }
  }

  /**
    *
    * @param consumOff
    * @param ku
    * @param topics
    * @param limit
    * @return
    */
  def getOffsetRange(
      consumOff: String,
      ku: KafkaUtil,
      topics: Set[String],
      limit: Long,
      latestOrEarliest: String = "latest"): Array[(String, Int, Long, Long)] = {
    val fromOff = if (consumOff == null) {
      if (latestOrEarliest.equalsIgnoreCase("latest")) {
        ku.getLastestOffset(topics)
      } else {
        ku.getEarleastOffset(topics)
      }
    } else {
      val j = JSON.parseObject(consumOff)
      topics
        .flatMap(topic => {
          val earliestOff = ku.getEarleastOffset(Set(topic))
          val lastestOff = ku.getLastestOffset(Set(topic))
          if (j.containsKey(topic)) { // 消费过
            val t1 = j.getJSONObject(topic)
            earliestOff.map {
              case (tp, l) =>
                val p = tp.partition()
                if (t1.containsKey(p.toString)) {
                  val consumOffset = t1.getString(p.toString).toString.toLong
                  if (consumOffset < l) {
                    _log.warn(s"offset 过期 ： $tp $consumOffset $l")
                    if (lastestOff(tp) < consumOffset) {
                      new TopicPartition(topic, p) -> lastestOff(tp)
                    } else {
                      new TopicPartition(topic, p) -> l
                    }
                  } else {
                    if (lastestOff(tp) < consumOffset) {
                      new TopicPartition(topic, p) -> lastestOff(tp)
                    } else {
                      new TopicPartition(topic, p) -> consumOffset
                    }
                  }
                } else {
                  new TopicPartition(topic, p) -> l
                }
            }
          } else {
            earliestOff
          }
        })
        .toMap
    }
    val lo = ku.getLastestOffset(topics)
    val offsetRange = fromOff.map {
      case (tp, l) =>
        if (lo.contains(tp)) {
          (tp.topic(),
           tp.partition(),
           l,
           Math.min(lo(tp), if (limit > 0) (l + limit) else lo(tp)))
        } else {
          (tp.topic(), tp.partition(), 0L, l)
        }
    }.toArray
    offsetRange
  }

  /**
    *
    * @param tpic_untiloff
    * @return
    */
  def offsetToJsonStr(consumOff: String,
                      tpic_untiloff: Array[(String, (Int, Long))]): String = {
    // scalastyle:off
    val consumOffJson =
      JSON.parseObject(if (consumOff == null) "{}" else consumOff)
    val ofssetS = JSON.parseObject("{}")
    tpic_untiloff
      .groupBy(_._1)
      .map {
        case (topic, l) => {
          val j = JSON.parseObject("{}")
          l.foreach {
            case (_, (p, l)) =>
              j.put(p.toString, l)
          }
          consumOffJson.put(topic, j)
        }
      }
    consumOffJson.toString
    // scalastyle:on
  }
  def main(args: Array[String]): Unit = {
    val kafkabroker =
      "localhost:9092"
    val map = new java.util.HashMap[String, Object]
    map.put("bootstrap.servers", kafkabroker)
    map.put("key.deserializer",
            "org.apache.kafka.common.serialization.StringDeserializer")
    map.put("value.deserializer",
            "org.apache.kafka.common.serialization.StringDeserializer")
    map.put("group.id", "test");
    map.put("enable.auto.commit", "false")
    val ku = KafkaUtil(map)

  }
}
