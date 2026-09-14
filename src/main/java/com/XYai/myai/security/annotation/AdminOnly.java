package com.XYai.myai.security.annotation;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.*;

/**
 * Restricts the annotated type/method to admin or org-admin roles.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAnyRole('ADMIN', 'ORG_ADMIN')")
public @interface AdminOnly {
}
