package br.com.ricarte.hookguard.billing;

import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;

/** Reads the current state of a subscription from the provider (GET /v1/subscriptions/{id}). */
public interface StripeSubscriptionGateway {

    Subscription retrieve(String subscriptionId) throws StripeException;
}
