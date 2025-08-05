package com.github.thxmasj.statemachine.templates.cardpayment;

public record AcquirerResponse(
    Long amount,
    String stan,
    String responseCode,
    String authorisationCode,
    Integer batchNumber,
    ReconciliationValues reconciliationValues
) {

  public record ReconciliationValues(
      Long creditAmount,
      Long creditCount,
      Long debitAmount,
      Long debitCount,
      Long creditReversalAmount,
      Long creditReversalCount,
      Long debitReversalAmount,
      Long debitReversalCount
  ) {}

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private Long amount;
    private String responseCode;
    private String stan;
    private String authorisationCode;
    private Integer batchNumber;
    private ReconciliationValues reconciliationValues;

    public Builder amount(Long amount) {
      this.amount = amount;
      return this;
    }

    public Builder stan(String stan) {
      this.stan = stan;
      return this;
    }

    public Builder responseCode(String responseCode) {
      this.responseCode = responseCode;
      return this;
    }

    public Builder authorisationCode(String authorisationCode) {
      this.authorisationCode = authorisationCode;
      return this;
    }

    public Builder batchNumber(Integer batchNumber) {
      this.batchNumber = batchNumber;
      return this;
    }

    public Builder reconciliationValues(ReconciliationValues reconciliationValues) {
      this.reconciliationValues = reconciliationValues;
      return this;
    }

    public AcquirerResponse build() {
      return new AcquirerResponse(
          amount,
          stan,
          responseCode,
          authorisationCode,
          batchNumber,
          reconciliationValues
      );
    }
  }
}
