package com.hmdp.upgrade;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;

@RestController
@RequestMapping("/v2/admin/cache")
public class CacheOperations implements MeterBinder {
    private final ShopCache cache;private final ShopBloom bloom;private final CacheCircuit circuit;
    CacheOperations(ShopCache cache,ShopBloom bloom,CacheCircuit circuit) {this.cache=cache;this.bloom=bloom;this.circuit=circuit;}
    @GetMapping public Object status() {return Map.of("cache",cache.metrics(),"bloom",bloom.metrics(),"circuit",circuit.status());}
    public record Degradation(boolean enabled) {}
    @PostMapping("/degradation") public Object degradation(@RequestBody Degradation input) {circuit.forceOpen(input.enabled());return circuit.status();}
    @PostMapping("/bloom/rebuild") public Object rebuild() {bloom.rebuild();return bloom.metrics();}
    @Override public void bindTo(MeterRegistry registry) {
        for(String metric: new String[]{"databaseReads","hits","staleHits","rebuildRejected","localHits","fallbacks","fallbackRejected","ttlHits"})
            FunctionCounter.builder("life.cache.events",cache,c->c.metrics().get(metric)).tag("kind",metric).register(registry);
        for(String metric:new String[]{"checks","blocked","bypassed"})
            FunctionCounter.builder("life.cache.bloom",bloom,b->b.metrics().get(metric)).tag("kind",metric).register(registry);
        Gauge.builder("life.cache.local.size",cache,c->c.metrics().get("localSize")).register(registry);
        Gauge.builder("life.cache.bloom.ready",bloom,b->b.metrics().get("ready")).register(registry);
        Gauge.builder("life.cache.circuit.state",circuit,CacheCircuit::stateCode).register(registry);
    }
}
