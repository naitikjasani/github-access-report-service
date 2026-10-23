package com.githubaccessreport.report.domain;

/** How a user relates to the organization. */
public enum Affiliation {
    /** Organization owner: implicitly admin on every repository. */
    OWNER,
    /** Organization member (access via teams, base permission or direct grant). */
    MEMBER,
    /** Not an organization member; granted access to specific repositories. */
    OUTSIDE_COLLABORATOR,
    /** The token could not read organization membership. */
    UNKNOWN
}
