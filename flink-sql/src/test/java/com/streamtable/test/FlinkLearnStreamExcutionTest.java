package com.streamtable.test;

import com.flink.common.core.CaseClassManager;
import com.flink.common.java.connect.PrintlnConnect;
import com.flink.common.java.pojo.KafkaTopicOffsetMsgPoJo;
import com.flink.common.java.pojo.WordCountPoJo;
import com.flink.common.java.tablesink.HbaseRetractStreamTableSink;
import com.flink.common.kafka.KafkaManager;
import com.flink.common.manager.SchemaManager;
import com.flink.common.manager.TableSourceConnectorManager;
import com.flink.java.function.common.util.AbstractHbaseQueryFunction;
import com.flink.java.function.process.HbaseQueryProcessFunction;
import com.flink.learn.sql.common.DDLSourceSQLManager;
import com.flink.learn.test.common.FlinkJavaStreamTableTestBase;
import com.flink.sql.common.format.ConnectorFormatDescriptorUtils;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.descriptors.Json;
import org.apache.flink.table.descriptors.Kafka;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.types.DataType;
import org.apache.flink.types.Row;
import org.apache.flink.util.OutputTag;
import org.apache.hadoop.hbase.client.Result;
import org.junit.Test;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

public class FlinkLearnStreamExcutionTest extends FlinkJavaStreamTableTestBase {
    /**
     * table 转stream
     *
     * @throws Exception
     */
    @Test
    public void testTableToStream() throws Exception {
        Table a = getStreamTable(
                getKafkaDataStream("test", "localhost:9092", "latest"),
                "topic,offset,msg");
        tableEnv.createTemporaryView("test", a);
        tableEnv.executeSql(DDLSourceSQLManager.createCustomPrintlnRetractSinkTbl("printlnSink_retract"));

        // 程序直接结束退出
        // tableEnv.executeSql("insert into printlnSink_retract select topic,msg,count(*) as ll from test group by topic,msg");

        Table b = tableEnv.sqlQuery("select topic,msg,count(*) as ll from test group by topic,msg");
        // 加tableEnv.execute报错：No operators defined in streaming topology
        b.executeInsert("printlnSink_retract");
         streamEnv.execute("jobname");
    }

    /**
     * table-》stream
     *
     * @throws Exception
     */
    @Test
    public void testTableToStream2() throws Exception {
        Table a = getStreamTable(
                getKafkaDataStream("test", "localhost:9092", "latest"),
                "topic,offset,msg");
        tableEnv.createTemporaryView("test", a);
        tableEnv.toRetractStream(
                tableEnv.sqlQuery("select topic,msg,count(*) as ll from test group by topic,msg"),
                Row.class)
                .print();
        streamEnv.execute("");
    }


    /**
     * stream 转 table ， table 转stream
     * @throws Exception
     */
    @Test
    public void testStreamToTable() throws Exception {
        Table a = getStreamTable(
                getKafkaDataStream("test", "localhost:9092", "latest"),
                "topic,offset,msg");
        tableEnv.createTemporaryView("test", a);

        DataStream stream = tableEnv.toRetractStream(
                tableEnv.sqlQuery("select topic,msg,count(*) as ll from test group by topic,msg"),
                Row.class)
                .filter(x -> x.f0)
                .map(x -> new Tuple3<String, String, Long>(x.f1.getField(0).toString(), x.f1.getField(1).toString(), Long.valueOf(x.f1.getField(2).toString())))
                .returns(Types.TUPLE(Types.STRING, Types.STRING, Types.LONG));
        ;
        tableEnv.createTemporaryView("tmptale", tableEnv.fromDataStream(stream, "topic,msg,ll"));
        tableEnv.from("tmptale").printSchema();
        tableEnv.toRetractStream(tableEnv.from("tmptale"), Row.class).print();

        streamEnv.execute("");
    }

    /**
     * @throws Exception
     */
    @Test
    public void testStreamJoin() throws Exception {
        Table a = getStreamTable(
                getKafkaDataStream("test", "localhost:9092", "latest"),
                "topic,offset,msg");
        Table a2 = getStreamTable(
                getKafkaDataStream("test", "localhost:9092", "latest"),
                "topic,offset,msg");

        tableEnv.createTemporaryView("test2", a2);
        tableEnv.createTemporaryView("test", a);

        tableEnv.toRetractStream(
                tableEnv.sqlQuery("select * from test a join test2 b on a.msg=b.msg"),
                Row.class)
                .print();

        tableEnv.toRetractStream(
                tableEnv.sqlQuery("select topic,msg,count(*) as ll from test group by topic,msg"),
                Row.class)
                .print();

        // 只追加数据，没有回溯历史数据可以用 append
//        tableEnv.toAppendStream(
//                tableEnv.sqlQuery("select msg from test"),
//                Row.class)
//                .print();
// 1.11 需要用 streamEnv.execute("jobname") 而不是 tableEnv.execute("")
        streamEnv.execute("jobname");
    }

    @Test
    public void testConnectStream() throws Exception {
        // {"id":"id2","name":"name2","age":1}
        Kafka kafkaConnector =
                TableSourceConnectorManager.kafkaConnector("localhost:9092", "test", "test", "latest");
        Json jsonFormat = ConnectorFormatDescriptorUtils.kafkaConnJsonFormat();
        tableEnv.executeSql(DDLSourceSQLManager.createCustomPrintlnRetractSinkTbl("printlnSink_retract"));
        tableEnv
                .connect(kafkaConnector)
                .withFormat(jsonFormat)
                .withSchema(SchemaManager.ID_NAME_AGE_SCHEMA())
                .inAppendMode()
                .createTemporaryTable("test");
        Table a =
                tableEnv.sqlQuery("select id as topic,name as msg,count(*) as ll from test group by id,name");
        a.insertInto("printlnSink_retract");
        //      tableEnv.toRetractStream(a, Row.class).print();

        tableEnv.execute("aa");

    }


    @Test
    public void testStreamTableSink() throws Exception {
        Table a = getStreamTable(
                getKafkaDataStream("test", "localhost:9092", "latest"),
                "topic,offset,msg").renameColumns("offset as ll");
        // sink1 : 转 stream后sink
        // tableEnv.toAppendStream(a, Row.class).print();

        // String sql="insert into hbasesink select topic,count(1) as c from test  group by topic";
        // tableEnv.sqlUpdate(sql);

        // 使用 connect的方式
        // sink3
//         TableSinkManager.connctKafkaSink(tableEnv, "test_sink_kafka");
        // a.insertInto("test_sink_kafka");

        // TableSinkManager.connectFileSystemSink(tableEnv, "test_sink_csv");
        // a.insertInto("test_sink_csv");


        // sink2 : 也是过期的，改用 connector方式 ，需要自己实现 TableSinkFactory .参考csv
        // TableSinkManager.registAppendStreamTableSink(tableEnv);
        // a.insertInto("test2");


        // sink4 : register 的方式已经过期，用conector的方式
//        String[] s = {"topic", "offset", "msg"};
//        TypeInformation[] ss = {Types.STRING, Types.LONG, Types.STRING};
//        TableSinkManager.registerJavaCsvTableSink(
//                tableEnv,
//                "test_sink_csv",
//                s,
//                ss,
//                "file:///Users/eminem/workspace/flink/flink-learn/checkpoint/data", // output path
//                "|", // optional: delimit files by '|'
//                1, // optional: write to a single file
//                FileSystem.WriteMode.OVERWRITE
//        );
        // a.insertInto("test_sink_csv");

        tableEnv.execute("");
    }

    /**
     * 将统计结果输出到hbase
     *
     * @throws Exception
     */
    @Test
    public void testcustomHbasesink() throws Exception {
        Table a = getStreamTable(
                getKafkaDataStream("test", "localhost:9092", "latest"),
                "topic,offset,msg");
        tableEnv.createTemporaryView("test", a);


//        // 可以转stream之后再转换。pojo可以直接对应上Row
//        SingleOutputStreamOperator<Tuple2<String, Row>> ds = tableEnv.toAppendStream(a, KafkaTopicOffsetMsgPoJo.class)
//                .map(new MapFunction<KafkaTopicOffsetMsgPoJo, Tuple2<String, Row>>() {
//                    @Override
//                    public Tuple2<String, Row> map(KafkaTopicOffsetMsgPoJo value) throws Exception {
//                        return new Tuple2<>(value.topic, Row.of(value.toString()));
//                    }
//                });
        // tableEnv.createTemporaryView("test", ds);
        // 方法1
        tableEnv.registerTableSink("hbasesink",
                new HbaseRetractStreamTableSink(new String[]{"topic", "c"},
                        new DataType[]{DataTypes.STRING(), DataTypes.BIGINT()
                        }));

        // 方法2
//        tableEnv.sqlUpdate(DDLSourceSQLManager.createCustomHbaseSinkTbl("hbasesink"));


        tableEnv.sqlQuery("select topic,count(1) as c from test  group by topic")
                .insertInto("hbasesink");
        tableEnv.execute("");

    }

    /**
     * join 维表，维表大
     * 批量查询hbase，
     *
     * @throws Exception
     */
    @Test
    public void testHbaseJoin() throws Exception {
        Table a = getStreamTable(
                getKafkaDataStream("test", "localhost:9092", "latest"),
                "topic,offset,msg");
        OutputTag<KafkaTopicOffsetMsgPoJo> queryFailed = new OutputTag<KafkaTopicOffsetMsgPoJo>("queryFailed") {
        };
        SingleOutputStreamOperator t = tableEnv
                .toAppendStream(a, KafkaTopicOffsetMsgPoJo.class)
                .keyBy((KeySelector<KafkaTopicOffsetMsgPoJo, String>) value -> value.msg)
                .process(new HbaseQueryProcessFunction<KafkaTopicOffsetMsgPoJo, WordCountPoJo>(
                        new AbstractHbaseQueryFunction<KafkaTopicOffsetMsgPoJo, WordCountPoJo>() {
                            @Override
                            public String getRowkey(KafkaTopicOffsetMsgPoJo input) {
                                return input.msg;
                            }

                            @Override
                            public void transResult(Tuple2<Result, KafkaTopicOffsetMsgPoJo> res, List<WordCountPoJo> result) {
                                if (res.f0 == null)
                                    result.add(new WordCountPoJo("joinfail", 1L));
                                else {
                                    result.add(new WordCountPoJo(res.f1.msg, 1L));
                                }
                            }
                        },
                        null,
                        100,
                        TypeInformation.of(KafkaTopicOffsetMsgPoJo.class),
                        queryFailed))
                .returns(TypeInformation.of(WordCountPoJo.class)).uid("uid").name("name");

        t.getSideOutput(queryFailed)
                .map(x -> "cant find : " + x.toString())
                .print();

        tableEnv.createTemporaryView("wcstream", t);

        // retract
        tableEnv.toRetractStream(
                tableEnv.sqlQuery("select word,sum(num) num from wcstream group by word"),
                TypeInformation.of(new TypeHint<Tuple2<String, Long>>() {
                })) // Row.class
                .print();
        // upsert
//        tableEnv.connect(new PrintlnConnect("printsink_upsert", 1, true))
//                .inRetractMode()
//                .withFormat(ConnectorFormatDescriptorUtils.kafkaConnJsonFormat())
//                .withSchema(SchemaManager.WORD_COUNT_SCHEMA())
//                .createTemporaryTable("printsink_upsert");
//        tableEnv.sqlUpdate("insert into printsink_upsert select word,sum(num) num from wcstream group by word");


        tableEnv.execute("");
    }


    /**
     * 时间的几种定义
     * 1: pt.proctime ： 默认就有 proctime属性，pt为它的命名
     * 2: user_action_time AS PROCTIME()
     */
    @Test
    public void testTimeAttributes() throws Exception {
        // {"id":"id2","name":"name","age":1}
        // 方法1 ： Processtime
//        Table a = getStreamTable(
//                getKafkaDataStream("test", "localhost:9092", "latest"),
//                "topic,offset,msg,pt.proctime")
//                .renameColumns("offset as ll"); // offset是关键字
//        tableEnv.createTemporaryView("test", a);
//        tableEnv.toAppendStream(a, Row.class).print();
//
//
//        // 方法2 ： Processtime
//        tableEnv.sqlUpdate(DDLSourceSQLManager.createStreamFromKafkaProcessTime(
//                "localhost:9092",
//                "localhost:2181",
//                "test", "test2", "test2"));
//        tableEnv.toAppendStream(tableEnv.from("test2"), Row.class).print();


        // {"id":"id2","name":"name","age":1,"etime":1596423467685}
// eventtime
//        Table a = getStreamTable(
//                getKafkaDataStreamWithEventTime("test", "localhost:9092", "latest")
//                        .assignTimestampsAndWatermarks(
//                                new BoundedOutOfOrdernessTimestampExtractor<KafkaManager.KafkaTopicOffsetMsgEventtime>(Time.seconds(10)) {
//                                    @Override
//                                    public long extractTimestamp(KafkaManager.KafkaTopicOffsetMsgEventtime element) {
//                                        return element.etime();
//                                    }
//                                }),
//                "topic,offset,msg,etime.rowtime");// offset是关键字
//        tableEnv.createTemporaryView("test", a);
//        tableEnv.toAppendStream(a, Row.class).print();

        // eventtime
        tableEnv.sqlUpdate(DDLSourceSQLManager.createStreamFromKafkaEventTime(
                "localhost:9092",
                "localhost:2181",
                "test", "test2", "test2"));

        tableEnv.toAppendStream(tableEnv.from("test2"), Row.class).print();


        tableEnv.execute("");

    }


}
