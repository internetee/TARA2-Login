package ee.ria.taraauthserver.config;

import ee.ria.taraauthserver.config.properties.AuthConfigurationProperties;
import ee.ria.taraauthserver.config.properties.EidasConfigurationProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.ignite.Ignite;
import org.apache.ignite.configuration.CacheConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;

import javax.cache.Cache;
import javax.net.ssl.SSLContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.apache.ignite.cache.CacheAtomicityMode.ATOMIC;
import static org.apache.ignite.cache.CacheMode.PARTITIONED;

@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(value = "tara.auth-methods.eidas.enabled", matchIfMissing = true)
public class EidasConfiguration {

    @Autowired
    EidasConfigurationProperties eidasConfigurationProperties;
    @Autowired
    @Qualifier("restTemplate")
    private RestTemplate restTemplate;

    @Scheduled(fixedRateString = "${tara.auth-methods.eidas.refresh-countries-interval-in-milliseconds}")
    public void scheduleFixedDelayTask() {
        log.info("starting fixed delay task");
        try {
            refreshCountriesList();
        } catch (Exception e) {
            log.error("Failed to update countries list - " + e.getMessage());
        }
    }

    private void refreshCountriesList() {
        String url = eidasConfigurationProperties.getClientUrl() + "/supportedCountries";
        log.info("requesting from: " + url);
        ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.GET, null, Object.class);
        eidasConfigurationProperties.setAvailableCountries((ArrayList<String>) response.getBody());
        log.info("updated countries list to: " + response.getBody().toString());
    }

    @Bean
    public Cache<String, String> eidasRelayStateCache(Ignite igniteInstance) {
        return igniteInstance.getOrCreateCache(new CacheConfiguration<String, String>()
                .setName("eidasRelayState")
                .setCacheMode(PARTITIONED)
                .setAtomicityMode(ATOMIC)
                .setBackups(0));
    }

    @LoadBalanced
    @Bean(value = "eidasRestTemplate")
    public RestTemplate eidasRestTemplate(RestTemplateBuilder builder, SSLContext sslContext, EidasConfigurationProperties eidasConfigurationProperties) {
        HttpClient client = HttpClients.custom()
                .setSSLContext(sslContext)
                .setMaxConnPerRoute(eidasConfigurationProperties.getMaxConnectionsTotal())
                .setMaxConnTotal(eidasConfigurationProperties.getMaxConnectionsTotal())
                .build();

        List<HttpMessageConverter<?>> converters = new ArrayList<HttpMessageConverter<?>>();
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setSupportedMediaTypes(Collections.singletonList(MediaType.TEXT_HTML));
        converters.add(converter);

        return builder
                .additionalMessageConverters(converters)
                .setConnectTimeout(Duration.ofSeconds(eidasConfigurationProperties.getRequestTimeoutInSeconds()))
                .setReadTimeout(Duration.ofSeconds(eidasConfigurationProperties.getReadTimeoutInSeconds()))
                .requestFactory(() -> new HttpComponentsClientHttpRequestFactory(client))
                .build();
    }

}