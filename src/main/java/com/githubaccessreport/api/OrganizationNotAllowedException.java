package com.githubaccessreport.api;

public class OrganizationNotAllowedException extends RuntimeException {

    public OrganizationNotAllowedException(String organization) {
        super("This deployment only reports on allow-listed organizations; '" + organization + "' is not one of them.");
    }
}
