-- Provisions the database for local runs. In other environments the platform provides the database, and the
-- application only needs a login which is member of the role given to the migrate admin process.
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'hello_world')
    CREATE LOGIN [hello_world] WITH PASSWORD = '$(HELLO_WORLD_PASSWORD)'
IF DB_ID('hello_world') IS NULL
    CREATE DATABASE [hello_world] COLLATE Norwegian_100_CS_AS_SC_UTF8
GO
USE [hello_world]
IF DATABASE_PRINCIPAL_ID('hello_world') IS NULL
    CREATE USER [hello_world] FOR LOGIN [hello_world]
IF DATABASE_PRINCIPAL_ID('HelloWorld') IS NULL
    CREATE ROLE [HelloWorld]
ALTER ROLE [HelloWorld] ADD MEMBER [hello_world]
GO
