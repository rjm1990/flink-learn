package com.flink.common.kafka

import java.util.Properties

import com.flink.common.deserialize.TopicOffsetMsgDeserialize
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer010, KafkaDeserializationSchema}

import scala.collection.JavaConversions._

object KafkaManager {
  /**
   *
   */
  def getKafkaParam(broker: String, groupID : String = "test") = {
    val pro = new Properties()
    pro.put("bootstrap.servers", broker)
    // pro.put("zookeeper.connect", KAFKA_ZOOKEEPER)
    pro.put("group.id", groupID)
    pro.put("auto.commit.enable", "false") //kafka 0.8-
    pro.put("enable.auto.commit", "false") //kafka 0.9+
    // pro.put("auto.commit.interval.ms", "60000")
    pro
  }

  case class KafkaMessge(topic: String, msg: String)
  case class KafkaTopicOffsetMsg(topic: String, offset: Long, msg: String)
//  case class KafkaTopicOffsetMsgEventtime(topic: String, offset: Long, msg: String, etime: Long)
  case class KafkaTopicOffsetTimeMsg(topic: String, offset: Long,  ts: Long, date: String, msg: String)

  /**
   *
   * @param topic
   * @param broker
   * @return
   */
  def getKafkaSource[T](topic: String,
                        broker: String,
                        deserialize: KafkaDeserializationSchema[T]): FlinkKafkaConsumer010[T] = {
    new FlinkKafkaConsumer010[T](topic.split(",").toList,
      deserialize,
      getKafkaParam(broker))
  }


  /**
   *
   * @param topic
   * @param broker
   * @return
   */
  def kafkaSource(
                   topic: String,
                   broker: String): FlinkKafkaConsumer010[KafkaTopicOffsetMsg] = {
    val kafkasource = KafkaManager.getKafkaSource(
      topic,
      broker,
      new TopicOffsetMsgDeserialize())
    kafkasource.setCommitOffsetsOnCheckpoints(true)
    kafkasource.setStartFromLatest() //不加这个默认是从上次消费
    kafkasource
  }
}
