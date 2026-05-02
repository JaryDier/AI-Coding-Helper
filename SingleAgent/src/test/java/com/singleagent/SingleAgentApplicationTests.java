package com.singleagent;

import org.bouncycastle.its.ITSValidityPeriod;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.Serializable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@SpringBootTest
class SingleAgentApplicationTests {

    @Test
    void contextLoads() throws InterruptedException {

        Flux<String> just = Flux.just("Hello", "World")
                .doOnSubscribe(s -> System.out.println("onSubscribe"));

        Flux<Long> interval = Flux.interval(Duration.of(1, ChronoUnit.SECONDS));


        Flux<? extends Serializable> publish = just.publish(stringFlux -> Flux.merge(
                stringFlux,
                stringFlux.collectList().flatMapMany(s -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    return Flux.just("Hello2", "World2");
                })
        ));

        Flux<Map<Long, Long>> mapFlux = interval.collectMap(item -> {
            return item;
        }).flatMapMany(map -> {
            return Flux.just(map);
        });

        Flux<Long> longFlux = interval.switchIfEmpty(Flux.never());


        publish.subscribe(System.out::println);

        Thread.sleep(60000);
    }

}
