package com.xavier.archfixture.forbidden.config;

import com.zaxxer.hikari.HikariDataSource;

/** ADR 0039 (S64): config may touch Hikari (it wraps the pool in the store-scope hook). */
class PoolInConfig {
    HikariDataSource pool;
}
