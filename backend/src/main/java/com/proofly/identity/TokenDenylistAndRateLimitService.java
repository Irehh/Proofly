package com.proofly.identity;

import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed Bucket4j rate limiter (for PIN & auth endpoints) and JTI token denylist.
 */
@Service
public class TokenDenylistAndRateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final RedisClient redisClient;
    private final ProxyManager<String> proxyManager;

    public TokenDenylistAndRateLimitService(
            StringRedisTemplate redisTemplate,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort) {
        this.redisTemplate = redisTemplate;
        this.redisClient = RedisClient.create(RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .build());
        var connection = redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        this.proxyManager = LettuceBasedProxyManager.builderFor(connection).build();
    }

    public void checkPinRateLimit(String key) {
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(5).refillIntervally(5, Duration.ofMinutes(5)).build())
                .build();
        Bucket bucket = proxyManager.builder().build("rl:pin:" + key, () -> config);
        if (!bucket.tryConsume(1)) {
            throw new ProoflyException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                    "Too many PIN login attempts. Please try again later.");
        }
    }

    public void checkAuthRateLimit(String key) {
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(20).refillGreedy(20, Duration.ofMinutes(1)).build())
                .build();
        Bucket bucket = proxyManager.builder().build("rl:auth:" + key, () -> config);
        if (!bucket.tryConsume(1)) {
            throw new ProoflyException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                    "Rate limit exceeded on authentication endpoint.");
        }
    }

    public void denyAccessToken(String jti, Duration ttl) {
        if (jti != null && !jti.isBlank()) {
            redisTemplate.opsForValue().set("denylist:jti:" + jti, "1", ttl);
        }
    }

    public boolean isAccessTokenDenied(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey("denylist:jti:" + jti));
    }

    @PreDestroy
    public void close() {
        redisClient.shutdown();
    }
}
