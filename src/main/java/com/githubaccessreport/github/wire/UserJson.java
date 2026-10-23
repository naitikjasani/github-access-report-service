package com.githubaccessreport.github.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserJson(long id, String login) {}
