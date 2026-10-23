package com.githubaccessreport.report.domain;

/** A repository whose collaborators could not be read. It is reported, never silently dropped. */
public record SkippedRepository(String name, Reason reason) {

    public enum Reason {
        /** The token lacks the access GitHub requires to list collaborators (write or higher). */
        INSUFFICIENT_PERMISSION,
        /** Listed, then gone (deleted or renamed while the report was being built). */
        NOT_FOUND
    }
}
