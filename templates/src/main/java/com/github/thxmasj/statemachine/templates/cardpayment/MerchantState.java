package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.State;

public enum MerchantState implements State {
  Begin, Active, Suspended, Deleted
}
