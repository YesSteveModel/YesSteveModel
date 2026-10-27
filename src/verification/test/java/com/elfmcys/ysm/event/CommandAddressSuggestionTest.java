package com.elfmcys.ysm.event;

import com.mojang.brigadier.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandAddressSuggestionTest {
    @Test
    void quotingRoundTripsEveryAddressWithoutChangingItsValue() throws Exception {
        var quote = CommandRegistry.class.getDeclaredMethod("filterSuggestionStr", String.class);
        quote.setAccessible(true);
        for (var address : List.of("default", "global:custom/team/model.mxc", "local:auth/a b/模型.mxc",
            "local:custom/a'quote.mxc", "global:auth/a\"quote.mxc", "builtin:%2e%2e")) {
            var reader = new StringReader((String)quote.invoke(null, address));
            assertEquals(address, reader.readString());
            assertFalse(reader.canRead());
        }
    }
}
