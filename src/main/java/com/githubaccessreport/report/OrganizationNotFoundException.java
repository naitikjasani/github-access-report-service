package com.githubaccessreport.report;

public class OrganizationNotFoundException extends RuntimeException {

    public OrganizationNotFoundException(String organization) {
        super("GitHub organization not found: " + organization);
    }
}
