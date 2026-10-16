#!/bin/sh
# A subshell also keeps entrypoint options intact when a Windows bind mount is sourced.
(
set -eu
# Passwords are environment-only. Escape SQL strings before passing to mysql stdin.
sql_escape() { printf '%s' "$1" | sed "s/'/''/g"; }
app=$(sql_escape "$FD_DB_PASSWORD")
report=$(sql_escape "$REPORT_DB_PASSWORD")
test=$(sql_escape "$TEST_DB_PASSWORD")
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
SET SESSION sql_mode='NO_BACKSLASH_ESCAPES';
CREATE DATABASE IF NOT EXISTS fd_db;
CREATE DATABASE IF NOT EXISTS fd_db_test;
CREATE DATABASE IF NOT EXISTS report_db_test;
CREATE USER 'fd_app'@'%' IDENTIFIED BY '$app';
CREATE USER 'fd_report'@'%' IDENTIFIED BY '$report';
CREATE USER 'fd_test'@'%' IDENTIFIED BY '$test';
GRANT ALL ON fd_db.* TO 'fd_app'@'%';
GRANT SELECT ON fd_db.* TO 'fd_report'@'%';
GRANT ALL ON fd_db_test.* TO 'fd_test'@'%';
GRANT ALL ON report_db_test.* TO 'fd_test'@'%';
SQL

)
