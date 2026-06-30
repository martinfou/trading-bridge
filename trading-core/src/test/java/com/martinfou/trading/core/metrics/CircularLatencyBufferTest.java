package com.martinfou.trading.core.metrics;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CircularLatencyBufferTest {

    @Test
    void testInitialState() {
        CircularLatencyBuffer buffer = new CircularLatencyBuffer(10);
        assertEquals(0.0, buffer.getAverage(), 0.0001);
        assertEquals(0.0, buffer.getMax(), 0.0001);
    }

    @Test
    void testBasicInsertions() {
        CircularLatencyBuffer buffer = new CircularLatencyBuffer(5);
        buffer.add(10.0);
        buffer.add(20.0);
        
        assertEquals(15.0, buffer.getAverage(), 0.0001);
        assertEquals(20.0, buffer.getMax(), 0.0001);
    }

    @Test
    void testCircularWrapping() {
        CircularLatencyBuffer buffer = new CircularLatencyBuffer(3);
        buffer.add(10.0);
        buffer.add(20.0);
        buffer.add(30.0);
        
        assertEquals(20.0, buffer.getAverage(), 0.0001);
        assertEquals(30.0, buffer.getMax(), 0.0001);
        
        // This should overwrite 10.0
        buffer.add(40.0);
        // Elements in buffer should now be: 40.0, 20.0, 30.0
        assertEquals(30.0, buffer.getAverage(), 0.0001);
        assertEquals(40.0, buffer.getMax(), 0.0001);
    }

    @Test
    void testIndexOverflowSafety() throws Exception {
        CircularLatencyBuffer buffer = new CircularLatencyBuffer(5);
        
        // Set index close to Integer.MAX_VALUE via reflection
        Field indexField = CircularLatencyBuffer.class.getDeclaredField("index");
        indexField.setAccessible(true);
        AtomicInteger index = (AtomicInteger) indexField.get(buffer);
        index.set(Integer.MAX_VALUE - 2);

        // This will increment index to MAX_VALUE - 1, and eventually overflow to negative values
        assertDoesNotThrow(() -> {
            buffer.add(10.0); // index was MAX_VALUE - 2
            buffer.add(20.0); // index was MAX_VALUE - 1
            buffer.add(30.0); // index was MAX_VALUE (overflows here to Integer.MIN_VALUE)
            buffer.add(40.0); // index was MIN_VALUE
            buffer.add(50.0); // index was MIN_VALUE + 1
        });

        // Verify values are retrieved normally without throwing ArrayIndexOutOfBoundsException
        assertEquals(40.0, buffer.getAverage(), 0.0001);
        assertEquals(50.0, buffer.getMax(), 0.0001);
    }

    @Test
    void testConcurrentInsertions() throws Exception {
        int threads = 8;
        int insertionsPerThread = 1000;
        CircularLatencyBuffer buffer = new CircularLatencyBuffer(10);
        
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < insertionsPerThread; i++) {
                        buffer.add(15.5);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();

        // Since all inputs are 15.5, average and max must be exactly 15.5
        assertEquals(15.5, buffer.getAverage(), 0.0001);
        assertEquals(15.5, buffer.getMax(), 0.0001);
    }
}
