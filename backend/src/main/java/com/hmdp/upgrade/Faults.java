package com.hmdp.upgrade;

import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Faults {
    private final boolean enabled;
    private final Path directory;
    public Faults(@Value("${upgrade.faults:false}") boolean enabled,
        @Value("${upgrade.fault-directory:target/faults}") String directory) {
        this.enabled=enabled;this.directory=Path.of(directory);
    }
    public void hit(String point) {
        if(!enabled) return;
        try {
            if(Files.deleteIfExists(directory.resolve(point))) Runtime.getRuntime().halt(91);
        } catch(java.io.IOException e) { throw new IllegalStateException("Cannot read local fault marker",e); }
    }
}
