-- Provisions the database for local runs. In other environments the platform provides the database, and the demo
-- only needs a login which is member of the role given to the migrate admin process.
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'demo')
    CREATE LOGIN [demo] WITH PASSWORD = '$(DEMO_PASSWORD)'
IF DB_ID('demo') IS NULL
    CREATE DATABASE [demo] COLLATE Norwegian_100_CS_AS_SC_UTF8
GO
USE [demo]
IF DATABASE_PRINCIPAL_ID('demo') IS NULL
    CREATE USER [demo] FOR LOGIN [demo]
IF DATABASE_PRINCIPAL_ID('Demo') IS NULL
    CREATE ROLE [Demo]
ALTER ROLE [Demo] ADD MEMBER [demo]
GO
