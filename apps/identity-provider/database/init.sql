-- Provisions the database for local runs. In other environments the platform provides the database, and the identity
-- provider only needs a login which is member of the role given to the migrate admin process.
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'idp')
    CREATE LOGIN [idp] WITH PASSWORD = '$(IDP_PASSWORD)'
IF DB_ID('identity_provider') IS NULL
    CREATE DATABASE [identity_provider] COLLATE Norwegian_100_CS_AS_SC_UTF8
GO
USE [identity_provider]
IF DATABASE_PRINCIPAL_ID('idp') IS NULL
    CREATE USER [idp] FOR LOGIN [idp]
IF DATABASE_PRINCIPAL_ID('IdentityProvider') IS NULL
    CREATE ROLE [IdentityProvider]
ALTER ROLE [IdentityProvider] ADD MEMBER [idp]
GO
