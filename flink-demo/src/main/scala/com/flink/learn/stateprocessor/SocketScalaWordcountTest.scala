package com.flink.learn.stateprocessor

import java.util.Date

import com.flink.common.core.{EnvironmentalKey, FlinkEvnBuilder, FlinkLearnPropertiesUtil}
import com.flink.common.core.FlinkLearnPropertiesUtil.{FLINK_DEMO_CHECKPOINT_PATH, param}
import com.flink.learn.bean.WordCountPoJo
import org.apache.flink.api.common.functions.RichFlatMapFunction
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.configuration.Configuration
import org.apache.flink.util.Collector
import org.apache.flink.streaming.api.scala._
import com.flink.learn.bean.CaseClassUtil.Wordcount

object SocketScalaWordcountTest {
// nc -l 9877
  def main(args: Array[String]): Unit = {
    FlinkLearnPropertiesUtil.init(EnvironmentalKey.LOCAL_PROPERTIES_PATH,
      "SocketScalaWordcountTest")
    val env = FlinkEvnBuilder.buildStreamingEnv(param,
      FLINK_DEMO_CHECKPOINT_PATH,
      10000)
   val source = env.socketTextStream("localhost", 9877)
    source
      .map(x => {
        Wordcount(x, 1L, new Date().getTime)
      })
      .keyBy(b => b.word)  // keyBy("word") 的话用string读不出来
      .flatMap(
        // -----------
        new RichFlatMapFunction[Wordcount, Wordcount] {
        var lastState: ValueState[Wordcount] = _
        override def flatMap(value: Wordcount,
                             out: Collector[Wordcount]): Unit = {
          var ls = lastState.value()
          if (ls == null) {
            ls = value
          } else {
            ls.count += value.count
          }
          lastState.update(ls)
          out.collect(ls)
        }
        override def open(parameters: Configuration): Unit = {
          try {
            val desc = new ValueStateDescriptor[Wordcount](
              "wordcountState",
              createTypeInformation[Wordcount])
            lastState = getRuntimeContext().getState(desc)
          } catch {
            case e: Throwable => e.printStackTrace()
          }
        }
      }
      // ----------------
      )
      .uid("wordcountUID")
      .print()
    env.execute("SocketWordcountTest")
  }
}
