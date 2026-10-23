package com.githubaccessreport.report.domain;

/** Keyed by {@code id}: logins can be renamed, ids cannot. */
public record User(long id, String login) {}
