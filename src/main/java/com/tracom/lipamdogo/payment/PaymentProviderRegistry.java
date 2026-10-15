package com.tracom.lipamdogo.payment;

import com.tracom.lipamdogo.model.PaymentChannel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Channel → adapter registry. Adding a real provider later means adding one
 * bean that implements {@link PaymentProvider}; the engine is untouched.
 */
@Component
public class PaymentProviderRegistry {

    private final Map<PaymentChannel, PaymentProvider> providers = new EnumMap<>(PaymentChannel.class);

    public PaymentProviderRegistry(List<PaymentProvider> providers) {
        for (PaymentProvider provider : providers) {
            this.providers.put(provider.channel(), provider);
        }
    }

    public PaymentProvider require(PaymentChannel channel) {
        PaymentProvider provider = providers.get(channel);
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Payment channel " + channel + " has no provider adapter in this phase.");
        }
        return provider;
    }
}
