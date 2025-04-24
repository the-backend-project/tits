CREATE LOGIN [testlogin] WITH PASSWORD='Please_hide_me!'
CREATE DATABASE [work] COLLATE Norwegian_100_CS_AS_SC_UTF8
GO
USE [work]
CREATE USER [testuser] FOR LOGIN [testlogin]
CREATE ROLE [Test];
ALTER ROLE [Test] ADD MEMBER [testuser];
