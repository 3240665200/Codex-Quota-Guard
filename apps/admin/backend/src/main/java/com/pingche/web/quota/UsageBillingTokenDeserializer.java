package com.pingche.web.quota;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Do not let JSON floating-point coercion silently round an administrator's Token counts. */
public final class UsageBillingTokenDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser,DeserializationContext context) {
        if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT)
            return context.reportInputMismatch(Long.class,"Token counts must be JSON integers");
        return parser.getLongValue();
    }
}
