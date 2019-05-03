/*
 * Copyright 2018 Qunar, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qunar.tc.qmq.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import qunar.tc.qmq.Message;
import qunar.tc.qmq.consumer.annotation.QmqConsumer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderChangedConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(OrderChangedConsumer.class);

    private static Map<String, Message> messages = new ConcurrentHashMap<>();

    private static synchronized boolean check(String messageId) {
        if (messages.size() > 3) {
            messages.remove(messageId);
            LOG.info("current messages = ({})", messages.keySet());
            return true;
        }
        return false;
    }

    @QmqConsumer(subject = "order.changed", consumerGroup = "cg1", executor = "workerExecutor")
    public void onMessage(Message message) {
        long orderId = message.getLongProperty("orderId");
        String name = message.getStringProperty("name");

        LOG.info("begin consume msg {}", message.getMessageId());

//        if (message.getMessageId().endsWith("3")) {
//            LOG.error("exit");
//            System.exit(1);
//        }

        messages.put(message.getMessageId(), message);

        while (true) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (check(message.getMessageId())) break;
        }

        //do work
        LOG.info("end consume msg {}", message.getMessageId());

    }
}
