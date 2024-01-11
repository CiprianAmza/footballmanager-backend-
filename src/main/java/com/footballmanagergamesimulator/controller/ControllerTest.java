package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Player;
import com.footballmanagergamesimulator.service.KafkaService;
import nonapi.io.github.classgraph.json.JSONDeserializer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.KafkaThread;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


@RestController
    public class ControllerTest {

        private final KafkaTemplate<String, String> kafkaTemplate;

        public ControllerTest(KafkaTemplate<String, String> kafkaTemplate) {
            this.kafkaTemplate = kafkaTemplate;
        }

    @GetMapping("/messages")
    public ResponseEntity<String> sendMessage(@RequestParam(name="ok") String message) throws IOException, ExecutionException, InterruptedException, FileNotFoundException {


//        Properties prop = new Properties();
//        prop.put("bootstrap.servers", "localhost:39092");
//        prop.put("group.id", "my-group");
//        prop.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
//        prop.put("value.deserializer", JsonDeserializer.class);
//        prop.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
//        prop.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
//        prop.put("trusted.packages", "com.footballmanagergamesimulator.model");
//
//
//        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(prop);
//        consumer.subscribe(Arrays.asList("TestTopic"));
//
//        int x = 10;
//        while (x > 0) {
//            x--;
//            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
//            System.out.println(x + " " + records);
//            for (ConsumerRecord<String, String> record : records) {
//                System.out.println("Received message: key=" + record.key() + ", value=" + record.value());
//            }
//        }





        Map<String, Object> props = new HashMap();
        props.put("bootstrap.servers", "localhost:39092");
        props.put("group.id", "my-group");
        props.put("acks", "all");
        props.put("key.serializer",   "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", JsonSerializer.class);

        KafkaTemplate<String, Object> kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));

        Player player = new Player();
        player.setName("Ciprian");
        player.setAge("27");
        player.setTeam("Inazuma");

        kafkaTemplate.send("TestTopic", message, player);


        props.put("bootstrap.servers", "localhost:29092");
        Player player2 = new Player();
        player2.setName("Edi");
        player2.setAge("27");
        player2.setTeam("Shadows");
        KafkaTemplate<String, Object> kafkaTemplate2 = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        kafkaTemplate2.send("TestTopic", message, player2);

        return ResponseEntity.ok("Message sent successfully");
    }

}
