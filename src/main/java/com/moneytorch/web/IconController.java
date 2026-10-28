package com.moneytorch.web;

import com.moneytorch.AppProperties;
import com.moneytorch.source.TliPricesClient;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/** Serves item icons, downloading each from TLI Prices once and caching it on disk. */
@RestController
public class IconController {
    private static final Pattern ID = Pattern.compile("\\d{1,16}");

    private final TliPricesClient tli;
    private final Path dir;

    public IconController(TliPricesClient tli, AppProperties props) throws IOException {
        this.tli = tli;
        this.dir = Path.of(props.getIconsDir());
        Files.createDirectories(dir);
    }

    @GetMapping("/icons/{id}.png")
    public ResponseEntity<byte[]> icon(@PathVariable String id) {
        if (!ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().build();
        }
        Path file = dir.resolve(id + ".png");
        try {
            byte[] body;
            if (Files.exists(file)) {
                body = Files.readAllBytes(file);
            } else {
                body = tli.icon(id);
                Files.write(file, body);
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                    .body(body);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
