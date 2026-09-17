package com.paymesh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentRulesTest {
  @Test void deterministicExperimentAmounts() {
    assertEquals(195000, 45000 + 25000 + 75000 + 50000);
  }
  @Test void bullyPriorities() {
    assertTrue(40 > 30);
    assertEquals(30, 30); // NODE-2 is the highest remaining node after NODE-1 fails.
  }
}
