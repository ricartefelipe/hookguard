package br.com.ricarte.hookguard.billing;

import br.com.ricarte.hookguard.config.HookguardProperties;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import org.springframework.stereotype.Component;

@Component
public class StripeApiSubscriptionGateway implements StripeSubscriptionGateway {

    private final HookguardProperties properties;

    public StripeApiSubscriptionGateway(HookguardProperties properties) {
        this.properties = properties;
    }

    @Override
    public Subscription retrieve(String subscriptionId) throws StripeException {
        RequestOptions options = RequestOptions.builder()
                .setApiKey(properties.billing().stripeApiKey())
                .setConnectTimeout(5_000)
                .setReadTimeout(10_000)
                .setMaxNetworkRetries(0)
                .build();
        return Subscription.retrieve(subscriptionId, options);
    }
}
