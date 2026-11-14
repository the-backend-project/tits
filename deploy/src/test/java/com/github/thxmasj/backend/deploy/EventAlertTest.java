package com.github.thxmasj.backend.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulumi.core.Output;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.test.Mocks;
import com.pulumi.test.PulumiTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

public class EventAlertTest {

  private static final String RULE = "azure-native:monitor:ScheduledQueryRule";
  private static final String ACTION_GROUP = "azure-native:monitor:ActionGroup";

  // The resources registered, with their inputs
  private final List<Mocks.ResourceArgs> registered = new CopyOnWriteArrayList<>();

  @AfterAll
  static void cleanup() {
    PulumiTest.cleanup();
  }

  @Test
  void aRuleIsCreatedForEachSeverityOnTheTracesOfTheApplication() {
    createAll(List.of(), 15);

    assertThat(registered).extracting(r -> r.type).containsOnly(RULE);
    assertThat(registered).extracting(r -> r.name)
        .containsExactlyInAnyOrder("demo-warning-events", "demo-error-events", "demo-critical-events");
    for (EventAlert alert : EventAlert.values()) {
      Map<String, Object> rule = rule(alert);
      assertThat(rule)
          .containsEntry("kind", "LogAlert")
          .containsEntry("scopes", List.of("insights-id"))
          .containsEntry("resourceGroupName", "group")
          .containsEntry("enabled", true)
          // An event has happened for good, so its alert does not resolve by itself
          .containsEntry("autoMitigate", false)
          .doesNotContainKey("actions");
      assertThat(condition(rule))
          .containsEntry("query", alert.query())
          .containsEntry("timeAggregation", "Count")
          .containsEntry("operator", "GreaterThan")
          .containsEntry("threshold", 0.0)
          .containsEntry("dimensions", List.of(
              Map.of("name", "entityModel", "operator", "Include", "values", List.of("*")),
              Map.of("name", "eventType", "operator", "Include", "values", List.of("*"))
          ));
    }
  }

  @Test
  void theAlertOfASeverityHasTheCorrespondingSeverityOfAzureMonitor() {
    createAll(List.of(), 15);

    assertThat(rule(EventAlert.Critical)).containsEntry("severity", 0.0);
    assertThat(rule(EventAlert.Error)).containsEntry("severity", 1.0);
    assertThat(rule(EventAlert.Warning)).containsEntry("severity", 2.0);
  }

  @Test
  void aRuleLooksAtThePeriodSinceItsPreviousEvaluation() {
    createAll(List.of(), 5);

    for (EventAlert alert : EventAlert.values())
      assertThat(rule(alert)).containsEntry("evaluationFrequency", "PT5M").containsEntry("windowSize", "PT5M");
  }

  @Test
  void theEmailAddressesAreNotifiedOfTheAlertsOfEverySeverity() {
    createAll(List.of("ops@example.com", "dev@example.com"), 15);

    assertThat(registered).filteredOn(r -> r.type.equals(ACTION_GROUP)).singleElement().satisfies(group ->
        assertThat(group.inputs)
            .containsEntry("location", "Global")
            .containsEntry("enabled", true)
            .containsEntry("groupShortName", "demo")
            .containsEntry("emailReceivers", List.of(
                Map.of("name", "ops@example.com", "emailAddress", "ops@example.com", "useCommonAlertSchema", true),
                Map.of("name", "dev@example.com", "emailAddress", "dev@example.com", "useCommonAlertSchema", true)
            ))
    );
    for (EventAlert alert : EventAlert.values())
      assertThat(rule(alert)).containsEntry("actions", Map.of("actionGroups", List.of("demo-id")));
  }

  @Test
  void theQuerySelectsTheLinesOfTheSeverityAsTheyAreWritten() {
    assertThat(EventAlert.Warning.query())
        .contains("message has \"WARNING\"")
        .contains("line.severity == \"WARNING\"")
        .doesNotContain("ERROR", "CRITICAL");
  }

  @Test
  void theQueryHasTheDimensionsAsColumns() {
    for (EventAlert alert : EventAlert.values())
      for (String dimension : EventAlert.DIMENSIONS)
        assertThat(alert.query()).containsPattern("\\n {4}" + dimension + " = ");
  }

  /// Creates the alerts of an application named demo, with mocks giving each resource its name and `-id` as its id
  private void createAll(List<String> emails, int frequency) {
    Mocks mocks = args -> {
      registered.add(args);
      return CompletableFuture.completedFuture(Mocks.ResourceResult.of(Optional.of(args.name + "-id"), args.inputs));
    };
    PulumiTest.withMocks(mocks)
        .runTest(_ -> EventAlert.createAll(
            "demo",
            Output.of("group"),
            Output.of("insights-id"),
            emails,
            frequency,
            CustomResourceOptions.Empty
        ))
        .throwOnError();
  }

  /// The inputs of the rule of the alert
  private Map<String, Object> rule(EventAlert alert) {
    return registered.stream()
        .filter(r -> r.type.equals(RULE) && r.name.equals("demo-" + alert.name().toLowerCase() + "-events"))
        .findFirst()
        .orElseThrow()
        .inputs;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> condition(Map<String, Object> rule) {
    return ((List<Map<String, Object>>) ((Map<String, Object>) rule.get("criteria")).get("allOf")).getFirst();
  }

}
