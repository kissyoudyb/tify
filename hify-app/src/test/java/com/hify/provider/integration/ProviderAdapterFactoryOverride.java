package com.hify.provider.integration;

import com.hify.provider.adapter.ProviderAdapterFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

@TestConfiguration
public class ProviderAdapterFactoryOverride {

    @Bean("providerAdapterFactoryOverride")
    @Primary
    public ProviderAdapterFactory providerAdapterFactory() {
        return mock(ProviderAdapterFactory.class);
    }
}
