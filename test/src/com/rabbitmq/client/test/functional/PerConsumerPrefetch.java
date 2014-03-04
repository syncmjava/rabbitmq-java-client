package com.rabbitmq.client.test.functional;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.QueueingConsumer;
import com.rabbitmq.client.QueueingConsumer.Delivery;
import com.rabbitmq.client.test.BrokerTestCase;

import java.io.IOException;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import static com.rabbitmq.client.test.functional.QosTests.drain;

public class PerConsumerPrefetch extends BrokerTestCase {
    private String q;

    @Override
    protected void createResources() throws IOException {
        q = channel.queueDeclare().getQueue();
    }

    private interface Closure {
        public void makeMore(Deque<Delivery> deliveries) throws IOException;
    }

    public void testSingleAck() throws IOException {
        testPrefetch(new Closure() {
            public void makeMore(Deque<Delivery> deliveries) throws IOException {
                for (Delivery del : deliveries) {
                    ack(del, false);
                }
            }
        });
    }

    public void testMultiAck() throws IOException {
        testPrefetch(new Closure() {
            public void makeMore(Deque<Delivery> deliveries) throws IOException {
                ack(deliveries.getLast(), true);
            }
        });
    }

    public void testSingleNack() throws IOException {
        for (final boolean requeue: Arrays.asList(false, true)) {
            testPrefetch(new Closure() {
                public void makeMore(Deque<Delivery> deliveries) throws IOException {
                    for (Delivery del : deliveries) {
                        nack(del, false, requeue);
                    }
                }
            });
        }
    }

    public void testMultiNack() throws IOException {
        for (final boolean requeue: Arrays.asList(false, true)) {
            testPrefetch(new Closure() {
                public void makeMore(Deque<Delivery> deliveries) throws IOException {
                    nack(deliveries.getLast(), true, requeue);
                }
            });
        }
    }

    public void testRecover() throws IOException {
        testPrefetch(new Closure() {
            public void makeMore(Deque<Delivery> deliveries) throws IOException {
                channel.basicRecover();
            }
        });
    }

    private void testPrefetch(Closure closure) throws IOException {
        QueueingConsumer c = new QueueingConsumer(channel);
        publish(q, 15);
        consume(c, 5, false);
        Deque<Delivery> deliveries = drain(c, 5);

        ack(channel.basicGet(q, false), false);
        drain(c, 0);

        closure.makeMore(deliveries);
        drain(c, 5);
    }

    public void testPrefetchOnEmpty() throws IOException {
        QueueingConsumer c = new QueueingConsumer(channel);
        publish(q, 5);
        consume(c, 10, false);
        drain(c, 5);
        publish(q, 10);
        drain(c, 5);
    }

    public void testAutoAckIgnoresPrefetch() throws IOException {
        QueueingConsumer c = new QueueingConsumer(channel);
        publish(q, 10);
        consume(c, 1, true);
        drain(c, 10);
    }

    public void testPrefetchZeroMeansInfinity() throws IOException {
        QueueingConsumer c = new QueueingConsumer(channel);
        publish(q, 10);
        consume(c, 0, false);
        drain(c, 10);
    }

    public void testPrefetchValidation() throws IOException {
        validationFail(-1);
        validationFail(new HashMap<String, Object>());
        validationFail("banana");
    }

    private void validationFail(Object badThing) throws IOException {
        Channel ch = connection.createChannel();
        QueueingConsumer c = new QueueingConsumer(ch);

        try {
            ch.basicConsume(q, false, args(badThing), c);
        } catch (IOException e) {
            checkShutdownSignal(AMQP.PRECONDITION_FAILED, e);
        }
    }

    private void publish(String q, int n) throws IOException {
        for (int i = 0; i < n; i++) {
            channel.basicPublish("", q, null, "".getBytes());
        }
    }

    private void consume(QueueingConsumer c, int prefetch, boolean autoAck) throws IOException {
        channel.basicConsume(q, autoAck, args(prefetch), c);
    }

    private void ack(Delivery del, boolean multi) throws IOException {
        channel.basicAck(del.getEnvelope().getDeliveryTag(), multi);
    }

    private void ack(GetResponse get, boolean multi) throws IOException {
        channel.basicAck(get.getEnvelope().getDeliveryTag(), multi);
    }

    private void nack(Delivery del, boolean multi, boolean requeue) throws IOException {
        channel.basicNack(del.getEnvelope().getDeliveryTag(), multi, requeue);
    }

    private Map<String, Object> args(Object prefetch) {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("x-prefetch", prefetch);
        return a;
    }
}
