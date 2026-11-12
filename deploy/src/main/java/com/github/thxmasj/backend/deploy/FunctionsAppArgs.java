package com.github.thxmasj.backend.deploy;

import com.pulumi.core.Output;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// What to deploy, and how.
///
/// @param image local container image of the application, for `linux/amd64`. Its entry point serves HTTP on `$PORT`,
///     and it reads its database configuration from `DATABASE_*` environment variables like the apps do.
/// @param migrateArgs arguments to the image's entry point which run the migrate admin process
/// @param environment additional environment variables for the application and the migrate admin process
/// @param secretEnvironment additional environment variables for the application only, kept secret by Pulumi, e.g.
///     keys and credentials
/// @param urlEnvironment names of environment variables for the application and the migrate admin process which are
///     set to the URL of the application, `https://` and its host name, e.g. the issuer of an identity provider
/// @param databaseRole role given access to the schema by the migrate admin process, and to the application's user
/// @param databaseSchema schema holding the state machine's tables, or `null` for the application's default
/// @param databaseCollation collation of the database
/// @param databaseMinCapacity minimum number of vCores of the serverless database while it is not paused
/// @param databaseMaxCapacity maximum number of vCores of the serverless database
/// @param databaseAutoPauseDelay minutes without connections before the database pauses, or -1 to never pause
/// @param databaseUseFreeLimit whether to use the free Azure SQL Database offer, of which a subscription has one,
///     pausing the database for the rest of the month when the free limit is used up
/// @param instanceMemory memory in MB of each instance of the function app: 512, 2048 or 4096
/// @param maximumInstanceCount maximum number of instances the function app scales out to
/// @param deployerIp public IP address of the machine running the deployment, which is allowed through the database
///     firewall to provision the database and run the migrate admin process
/// @param workDirectory directory where the deployment package is built
public record FunctionsAppArgs(
    String image,
    List<String> migrateArgs,
    Map<String, String> environment,
    Output<Map<String, String>> secretEnvironment,
    List<String> urlEnvironment,
    String databaseRole,
    String databaseSchema,
    String databaseCollation,
    double databaseMinCapacity,
    int databaseMaxCapacity,
    int databaseAutoPauseDelay,
    boolean databaseUseFreeLimit,
    int instanceMemory,
    int maximumInstanceCount,
    String deployerIp,
    Path workDirectory
) {}
