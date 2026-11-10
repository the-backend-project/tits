package com.github.thxmasj.statemachine.deploy;

import static java.util.stream.Collectors.joining;

import com.pulumi.azurenative.applicationinsights.Component;
import com.pulumi.azurenative.applicationinsights.ComponentArgs;
import com.pulumi.azurenative.applicationinsights.enums.ApplicationType;
import com.pulumi.azurenative.authorization.AuthorizationFunctions;
import com.pulumi.azurenative.authorization.RoleAssignment;
import com.pulumi.azurenative.authorization.RoleAssignmentArgs;
import com.pulumi.azurenative.authorization.enums.PrincipalType;
import com.pulumi.azurenative.operationalinsights.Workspace;
import com.pulumi.azurenative.operationalinsights.WorkspaceArgs;
import com.pulumi.azurenative.operationalinsights.enums.WorkspaceSkuNameEnum;
import com.pulumi.azurenative.operationalinsights.inputs.WorkspaceSkuArgs;
import com.pulumi.azurenative.resources.ResourceGroup;
import com.pulumi.azurenative.resources.ResourceGroupArgs;
import com.pulumi.azurenative.sql.Database;
import com.pulumi.azurenative.sql.DatabaseArgs;
import com.pulumi.azurenative.sql.FirewallRule;
import com.pulumi.azurenative.sql.FirewallRuleArgs;
import com.pulumi.azurenative.sql.Server;
import com.pulumi.azurenative.sql.ServerArgs;
import com.pulumi.azurenative.sql.enums.FreeLimitExhaustionBehavior;
import com.pulumi.azurenative.sql.enums.MinimalTlsVersion;
import com.pulumi.azurenative.sql.inputs.SkuArgs;
import com.pulumi.azurenative.storage.BlobContainer;
import com.pulumi.azurenative.storage.BlobContainerArgs;
import com.pulumi.azurenative.storage.StorageAccount;
import com.pulumi.azurenative.storage.StorageAccountArgs;
import com.pulumi.azurenative.storage.enums.Kind;
import com.pulumi.azurenative.storage.enums.SkuName;
import com.pulumi.azurenative.storage.outputs.EndpointsResponse;
import com.pulumi.azurenative.web.AppServicePlan;
import com.pulumi.azurenative.web.AppServicePlanArgs;
import com.pulumi.azurenative.web.WebApp;
import com.pulumi.azurenative.web.WebAppArgs;
import com.pulumi.azurenative.web.enums.AuthenticationType;
import com.pulumi.azurenative.web.enums.FunctionsDeploymentStorageType;
import com.pulumi.azurenative.web.enums.ManagedServiceIdentityType;
import com.pulumi.azurenative.web.enums.RuntimeName;
import com.pulumi.azurenative.web.inputs.FunctionAppConfigArgs;
import com.pulumi.azurenative.web.inputs.FunctionsDeploymentArgs;
import com.pulumi.azurenative.web.inputs.FunctionsDeploymentStorageArgs;
import com.pulumi.azurenative.web.inputs.FunctionsDeploymentStorageAuthenticationArgs;
import com.pulumi.azurenative.web.inputs.FunctionsRuntimeArgs;
import com.pulumi.azurenative.web.inputs.FunctionsScaleAndConcurrencyArgs;
import com.pulumi.azurenative.web.inputs.ManagedServiceIdentityArgs;
import com.pulumi.azurenative.web.inputs.NameValuePairArgs;
import com.pulumi.azurenative.web.inputs.SiteConfigArgs;
import com.pulumi.azurenative.web.inputs.SkuDescriptionArgs;
import com.pulumi.command.local.Command;
import com.pulumi.command.local.CommandArgs;
import com.pulumi.core.Output;
import com.pulumi.random.RandomPassword;
import com.pulumi.random.RandomPasswordArgs;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// An application built with the state machine, deployed to Azure Functions on the Flex Consumption plan with a
/// serverless Azure SQL database. The application scales to zero, and so does the database after some time without
/// connections.
///
/// The application is a container image like the demo's, see [FunctionsAppArgs#image()]. It is run as a custom
/// handler, see [FunctionPackage]. Deploying runs these steps on the deploying machine, which therefore needs the
/// Docker CLI, the Azure CLI, and access to the database:
///
/// 1. [ProvisionDatabase] creates the application's database user and role, with the server administrator's
///    credentials.
/// 2. The image runs the migrate admin process, with the server administrator's credentials.
/// 3. The Azure CLI deploys the package to the function app.
///
/// Each step runs again when its inputs change, for example the image for the last two steps.
public class FunctionsApp extends ComponentResource {

  /// Storage Blob Data Owner, which the Functions host needs on its storage account
  private static final String STORAGE_BLOB_DATA_OWNER = "b7e6dc6d-f1e8-4753-8033-0f276bb0955b";
  private static final String DATABASE_ADMIN_USER = "titsadmin";
  private static final String DATABASE_USER = "app";

  private final Output<String> url;
  private final Output<String> resourceGroupName;
  private final Output<String> functionAppName;
  private final Output<String> databaseServer;
  private final Output<String> databaseName;

  public FunctionsApp(String name, FunctionsAppArgs args, ComponentResourceOptions options) {
    super("tits:azure:FunctionsApp", name, options);
    if (!name.matches("[a-z][a-z0-9-]{0,39}"))
      throw new IllegalArgumentException("Name must be lower case letters, digits and hyphens, but is " + name);

    ContainerImage image = ContainerImage.inspect(args.image());
    Path imageDirectory = args.workDirectory().resolve(image.id().replace("sha256:", "").substring(0, 12));
    image.export(imageDirectory.resolve("rootfs"));
    FunctionPackage functionPackage = FunctionPackage.build(
        imageDirectory.resolve("rootfs"),
        image.command(),
        imageDirectory.resolve("package.zip")
    );

    var group = new ResourceGroup(name, ResourceGroupArgs.Empty, child());

    // Database

    var adminPassword = password(name + "-db-admin");
    var appPassword = password(name + "-db-app");
    var server = new Server(name, ServerArgs.builder()
        .resourceGroupName(group.name())
        .version("12.0")
        .administratorLogin(DATABASE_ADMIN_USER)
        .administratorLoginPassword(adminPassword.result())
        .minimalTlsVersion(MinimalTlsVersion._1_2)
        .build(), child());
    var databaseArgs = DatabaseArgs.builder()
        .resourceGroupName(group.name())
        .serverName(server.name())
        .sku(SkuArgs.builder()
            .name("GP_S_Gen5")
            .tier("GeneralPurpose")
            .family("Gen5")
            .capacity(args.databaseMaxCapacity())
            .build())
        .minCapacity(args.databaseMinCapacity())
        .autoPauseDelay(args.databaseAutoPauseDelay())
        .collation(args.databaseCollation())
        .requestedBackupStorageRedundancy("Local");
    if (args.databaseUseFreeLimit())
      databaseArgs.useFreeLimit(true).freeLimitExhaustionBehavior(FreeLimitExhaustionBehavior.AutoPause);
    var database = new Database(name, databaseArgs.build(), child());
    // The start and end address 0.0.0.0 allows connections from Azure services, like the function app
    var allowAzure = new FirewallRule(name + "-azure", FirewallRuleArgs.builder()
        .resourceGroupName(group.name())
        .serverName(server.name())
        .startIpAddress("0.0.0.0")
        .endIpAddress("0.0.0.0")
        .build(), child());
    var allowDeployer = new FirewallRule(name + "-deployer", FirewallRuleArgs.builder()
        .resourceGroupName(group.name())
        .serverName(server.name())
        .startIpAddress(args.deployerIp())
        .endIpAddress(args.deployerIp())
        .build(), child());

    Map<String, Output<String>> databaseEnvironment = new LinkedHashMap<>();
    databaseEnvironment.put("DATABASE_HOST", server.fullyQualifiedDomainName());
    databaseEnvironment.put("DATABASE_NAME", database.name());
    if (args.databaseSchema() != null)
      databaseEnvironment.put("DATABASE_SCHEMA", Output.of(args.databaseSchema()));
    args.environment().forEach((key, value) -> databaseEnvironment.put(key, Output.of(value)));

    var provision = new Command(name + "-provision-database", CommandArgs.builder()
        .create(shell(List.of(javaExecutable(), "-cp", classPath(), ProvisionDatabase.class.getName())))
        .environment(environment(Map.of(
            "DATABASE_HOST", server.fullyQualifiedDomainName(),
            "DATABASE_NAME", database.name(),
            "DATABASE_ADMIN_USER", Output.of(DATABASE_ADMIN_USER),
            "DATABASE_ADMIN_PASSWORD", adminPassword.result(),
            "DATABASE_USER", Output.of(DATABASE_USER),
            "DATABASE_PASSWORD", appPassword.result(),
            "DATABASE_ROLE", Output.of(args.databaseRole())
        )))
        .build(), child(database, allowDeployer));

    Map<String, Output<String>> migrateEnvironment = new LinkedHashMap<>(databaseEnvironment);
    migrateEnvironment.put("DATABASE_USER", Output.of(DATABASE_ADMIN_USER));
    migrateEnvironment.put("DATABASE_PASSWORD", adminPassword.result());
    migrateEnvironment.put("DATABASE_ROLE", Output.of(args.databaseRole()));
    List<String> migrate = new ArrayList<>(List.of("docker", "run", "--rm", "--platform", ContainerImage.PLATFORM));
    migrateEnvironment.keySet().forEach(key -> migrate.addAll(List.of("--env", key)));
    migrate.add(image.id());
    migrate.addAll(args.migrateArgs());
    var migration = new Command(name + "-migrate", CommandArgs.builder()
        .create(shell(migrate))
        .environment(environment(migrateEnvironment))
        .build(), child(provision));

    // Function app

    // Storage account names are at most 24 lower case letters and digits, including the 8 random characters added
    String storageName = name.replace("-", "");
    storageName = storageName.substring(0, Math.min(16, storageName.length()));
    var storage = new StorageAccount(storageName, StorageAccountArgs.builder()
        .resourceGroupName(group.name())
        .kind(Kind.StorageV2)
        .sku(com.pulumi.azurenative.storage.inputs.SkuArgs.builder().name(SkuName.Standard_LRS).build())
        .allowBlobPublicAccess(false)
        .allowSharedKeyAccess(false)
        .minimumTlsVersion("TLS1_2")
        .build(), child());
    var packages = new BlobContainer(name + "-packages", BlobContainerArgs.builder()
        .resourceGroupName(group.name())
        .accountName(storage.name())
        .build(), child());
    var workspace = new Workspace(name, WorkspaceArgs.builder()
        .resourceGroupName(group.name())
        .sku(WorkspaceSkuArgs.builder().name(WorkspaceSkuNameEnum.PerGB2018).build())
        .retentionInDays(30)
        .build(), child());
    var insights = new Component(name, ComponentArgs.builder()
        .resourceGroupName(group.name())
        .kind("web")
        .applicationType(ApplicationType.Web)
        .workspaceResourceId(workspace.id())
        .build(), child());
    var plan = new AppServicePlan(name, AppServicePlanArgs.builder()
        .resourceGroupName(group.name())
        .kind("functionapp")
        .reserved(true)
        .sku(SkuDescriptionArgs.builder().name("FC1").tier("FlexConsumption").build())
        .build(), child());

    List<Output<NameValuePairArgs>> settings = new ArrayList<>();
    settings.add(setting("AzureWebJobsStorage__accountName", storage.name()));
    settings.add(setting("APPLICATIONINSIGHTS_CONNECTION_STRING", insights.connectionString()));
    databaseEnvironment.forEach((key, value) -> settings.add(setting(key, value)));
    settings.add(setting("DATABASE_USER", Output.of(DATABASE_USER)));
    settings.add(setting("DATABASE_PASSWORD", appPassword.result()));

    var app = new WebApp(name, WebAppArgs.builder()
        .resourceGroupName(group.name())
        .kind("functionapp,linux")
        .serverFarmId(plan.id())
        .httpsOnly(true)
        .identity(ManagedServiceIdentityArgs.builder().type(ManagedServiceIdentityType.SystemAssigned).build())
        .siteConfig(Output.all(settings).applyValue(s -> SiteConfigArgs.builder()
            .appSettings(s)
            .minTlsVersion("1.2")
            .build()))
        .functionAppConfig(FunctionAppConfigArgs.builder()
            .deployment(FunctionsDeploymentArgs.builder()
                .storage(FunctionsDeploymentStorageArgs.builder()
                    .type(FunctionsDeploymentStorageType.BlobContainer)
                    .value(Output.format("%s%s", storage.primaryEndpoints().applyValue(EndpointsResponse::blob),
                        packages.name()))
                    .authentication(FunctionsDeploymentStorageAuthenticationArgs.builder()
                        .type(AuthenticationType.SystemAssignedIdentity)
                        .build())
                    .build())
                .build())
            .runtime(FunctionsRuntimeArgs.builder().name(RuntimeName.Custom).version("1.0").build())
            .scaleAndConcurrency(FunctionsScaleAndConcurrencyArgs.builder()
                .instanceMemoryMB(args.instanceMemory())
                .maximumInstanceCount(args.maximumInstanceCount())
                .build())
            .build())
        .build(), child());
    var storageAccess = new RoleAssignment(name + "-storage", RoleAssignmentArgs.builder()
        .scope(storage.id())
        .roleDefinitionId(AuthorizationFunctions.getClientConfig().applyValue(c ->
            "/subscriptions/" + c.subscriptionId() + "/providers/Microsoft.Authorization/roleDefinitions/"
                + STORAGE_BLOB_DATA_OWNER))
        .principalId(app.identity().applyValue(i -> i.orElseThrow().principalId()))
        .principalType(PrincipalType.ServicePrincipal)
        .build(), child());

    new Command(name + "-deploy-package", CommandArgs.builder()
        .create(shell(List.of("az", "functionapp", "deployment", "source", "config-zip", "--resource-group",
            "$RESOURCE_GROUP", "--name", "$FUNCTION_APP", "--src", functionPackage.file().toAbsolutePath().toString())))
        .environment(environment(Map.of("RESOURCE_GROUP", group.name(), "FUNCTION_APP", app.name())))
        .triggers(functionPackage.sha256())
        .build(), child(migration, storageAccess, allowAzure));

    this.url = Output.format("https://%s/", app.defaultHostName());
    this.resourceGroupName = group.name();
    this.functionAppName = app.name();
    this.databaseServer = server.fullyQualifiedDomainName();
    this.databaseName = database.name();
    registerOutputs(Map.of(
        "url", url,
        "resourceGroupName", resourceGroupName,
        "functionAppName", functionAppName,
        "databaseServer", databaseServer,
        "databaseName", databaseName
    ));
  }

  public Output<String> url() {
    return url;
  }

  public Output<String> resourceGroupName() {
    return resourceGroupName;
  }

  public Output<String> functionAppName() {
    return functionAppName;
  }

  public Output<String> databaseServer() {
    return databaseServer;
  }

  public Output<String> databaseName() {
    return databaseName;
  }

  private CustomResourceOptions child(Resource... dependencies) {
    return CustomResourceOptions.builder().parent(this).dependsOn(dependencies).build();
  }

  private RandomPassword password(String name) {
    return new RandomPassword(name, RandomPasswordArgs.builder()
        .length(32)
        .special(false)
        .minLower(1)
        .minUpper(1)
        .minNumeric(1)
        .build(), child());
  }

  private static Output<NameValuePairArgs> setting(String name, Output<String> value) {
    return value.applyValue(v -> NameValuePairArgs.builder().name(name).value(v).build());
  }

  private static Output<Map<String, String>> environment(Map<String, Output<String>> environment) {
    List<String> keys = List.copyOf(environment.keySet());
    return Output.all(keys.stream().map(environment::get).toList()).applyValue(values -> {
      Map<String, String> map = new LinkedHashMap<>();
      for (int i = 0; i < keys.size(); i++)
        map.put(keys.get(i), values.get(i));
      return map;
    });
  }

  /// A shell command line running the given command. Arguments starting with `$` are environment variables.
  private static String shell(List<String> command) {
    return command.stream()
        .map(a -> a.startsWith("$") ? "\"" + a + "\"" : "'" + a.replace("'", "'\\''") + "'")
        .collect(joining(" "));
  }

  private static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static String classPath() {
    return Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
        .map(p -> Path.of(p).toAbsolutePath().toString())
        .collect(joining(File.pathSeparator));
  }

}
