package com.xavier.backend.catalog;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Starts concurrent requests at the same moment behind a latch and returns their results in order. */
public final class Race {

    private Race() {
    }

    public static <T> List<T> run(List<Callable<T>> requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(requests.size())) {
            List<Future<T>> futures = requests.stream().map(request -> pool.submit(() -> {
                start.await();
                return request.call();
            })).toList();
            start.countDown();
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    public static <T> List<T> twice(Callable<T> request) throws Exception {
        return run(List.of(request, request));
    }
}
