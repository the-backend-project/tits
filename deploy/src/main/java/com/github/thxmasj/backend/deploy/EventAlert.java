package com.github.thxmasj.backend.deploy;

import com.pulumi.azurenative.monitor.ActionGroup;
import com.pulumi.azurenative.monitor.ActionGroupArgs;
import com.pulumi.azurenative.monitor.ScheduledQueryRule;
import com.pulumi.azurenative.monitor.ScheduledQueryRuleArgs;
import com.pulumi.azurenative.monitor.enums.ConditionOperator;
import com.pulumi.azurenative.monitor.enums.DimensionOperator;
import com.pulumi.azurenative.monitor.enums.Kind;
import com.pulumi.azurenative.monitor.enums.TimeAggregation;
import com.pulumi.azurenative.monitor.inputs.ActionsArgs;
import com.pulumi.azurenative.monitor.inputs.ConditionArgs;
import com.pulumi.azurenative.monitor.inputs.ConditionFailingPeriodsArgs;
import com.pulumi.azurenative.monitor.inputs.DimensionArgs;
import com.pulumi.azurenative.monitor.inputs.MicrosoftCommonEmailReceiverArgs;
import com.pulumi.azurenative.monitor.inputs.ScheduledQueryRuleCriteriaArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.CustomResourceOptions;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/// The alert on the events of a severity, for each of the severities which call for attention. An application writes
/// its events for monitoring as lines of JSON to stdout, with `JsonLinesListener` of the core module, which the
/// Functions host logs as traces in Application Insights. The alert is a log search alert of Azure Monitor on the
/// traces which are lines of the severity. It is split by the dimensions, so an alert is fired for each entity model
/// and event type which has events of the severity.
public enum EventAlert {

  Warning(2),
  Error(1),
  Critical(0);

  /// The columns of the query which the alert is split by
  public static final List<String> DIMENSIONS = List.of("entityModel", "eventType");

  private final int azureSeverity;

  EventAlert(int azureSeverity) {
    this.azureSeverity = azureSeverity;
  }

  /// The severity of the alert in Azure Monitor, where 0 is critical, 1 is error and 2 is warning
  public int azureSeverity() {
    return azureSeverity;
  }

  /// The severity as it is written in the lines
  public String severity() {
    return name().toUpperCase(Locale.ROOT);
  }

  /// The query of the alert, on the tables of Application Insights: the lines of the severity, which the alert
  /// counts. The other lines the application writes are not JSON, or have no such severity. `has` finds the lines
  /// with the severity in the index of the table, so only those are parsed. A line which is not of an event, such as a
  /// failure of a worker of the state machine, has no entity model or event type, and gets a placeholder as its
  /// dimensions.
  public String query() {
    return """
        traces
        | where message startswith "{" and message has "%1$s"
        | extend line = parse_json(message)
        | where line.severity == "%1$s"
        | extend entityModel = tostring(line.entityModel), eventType = tostring(line.eventType)
        | project
            timestamp,
            entityModel = iff(isempty(entityModel), "-", entityModel),
            eventType = iff(isempty(eventType), "-", eventType),
            entityId = tostring(line.entityId),
            eventNumber = toint(line.eventNumber),
            correlationId = tostring(line.correlationId),
            message = tostring(line.message)
        """.formatted(severity());
  }

  /// Creates the alert rule of every severity, on the traces of an Application Insights component.
  ///
  /// Every evaluation of a rule looks at the period since the previous one, so every event is counted once. A fired
  /// alert is not resolved when a period passes without events, as its events have happened for good.
  ///
  /// @param name name of the application, which the resources are named after
  /// @param emails email addresses notified of the alerts. With none, the alerts are seen in Azure Monitor only.
  /// @param frequency minutes between the evaluations of a rule
  public static void createAll(
      String name,
      Output<String> resourceGroupName,
      Output<String> applicationInsightsId,
      List<String> emails,
      int frequency,
      CustomResourceOptions options
  ) {
    ActionGroup notification = emails.isEmpty() ? null : new ActionGroup(name, ActionGroupArgs.builder()
        .resourceGroupName(resourceGroupName)
        // Action groups are not regional
        .location("Global")
        // Shown in the notifications, and at most 12 characters
        .groupShortName(name.substring(0, Math.min(12, name.length())))
        .enabled(true)
        .emailReceivers(emails.stream()
            .map(email -> MicrosoftCommonEmailReceiverArgs.builder()
                .name(email)
                .emailAddress(email)
                .useCommonAlertSchema(true)
                .build())
            .toList())
        .build(), options);
    String period = Duration.ofMinutes(frequency).toString();
    for (EventAlert alert : values()) {
      var rule = ScheduledQueryRuleArgs.builder()
          .resourceGroupName(resourceGroupName)
          .kind(Kind.LogAlert)
          .displayName(name + ": " + alert.name() + " events")
          .description("Events of severity " + alert.severity() + " which " + name + " has written for monitoring, by"
              + " entity model and event type")
          .severity((double) alert.azureSeverity())
          .enabled(true)
          .scopes(applicationInsightsId.applyValue(List::of))
          .evaluationFrequency(period)
          .windowSize(period)
          .criteria(ScheduledQueryRuleCriteriaArgs.builder()
              .allOf(ConditionArgs.builder()
                  .query(alert.query())
                  .timeAggregation(TimeAggregation.Count)
                  .operator(ConditionOperator.GreaterThan)
                  .threshold(0.0)
                  .dimensions(DIMENSIONS.stream()
                      .map(dimension -> DimensionArgs.builder()
                          .name(dimension)
                          .operator(DimensionOperator.Include)
                          .values("*")
                          .build())
                      .toList())
                  .failingPeriods(ConditionFailingPeriodsArgs.builder()
                      .numberOfEvaluationPeriods(1.0)
                      .minFailingPeriodsToAlert(1.0)
                      .build())
                  .build())
              .build())
          .autoMitigate(false);
      if (notification != null)
        rule.actions(ActionsArgs.builder().actionGroups(notification.id().applyValue(List::of)).build());
      new ScheduledQueryRule(name + "-" + alert.name().toLowerCase(Locale.ROOT) + "-events", rule.build(), options);
    }
  }

}
