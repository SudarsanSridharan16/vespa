// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.application;

import com.yahoo.io.IOUtils;
import org.junit.Test;

import java.nio.file.Files;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * @author Tony Vaagenes
 * @author ollivir
 */
public class ApplicationBuilderTest {
    @Test
    public void query_profile_types_can_be_added() throws Exception {
        withApplicationBuilder(builder -> {
            builder.queryProfileType("MyProfileType", "<query-profile-type id=\"MyProfileType\">" + //
            "<field name=\"age\" type=\"integer\" />" + //
            "<field name=\"profession\" type=\"string\" />" + //
            "<field name=\"user\" type=\"query-profile:MyUserProfile\" />" + //
            "</query-profile-type>");

            assertTrue(Files.exists(builder.getPath().resolve("search/query-profiles/types/MyProfileType.xml")));
        });
    }

    @Test
    public void query_profile_can_be_added() throws Exception {
        withApplicationBuilder(builder -> {
            builder.queryProfile("MyProfile",
                                 "<query-profile id=\"MyProfile\">" +
                                 "<field name=\"message\">Hello world!</field>" +
                                 "</query-profile>");

            assertTrue(Files.exists(builder.getPath().resolve("search/query-profiles/MyProfile.xml")));
        });
    }

    @Test
    public void rank_expression_can_be_added() throws Exception {
        withApplicationBuilder(builder -> {
            builder.rankExpression("myExpression", "content");
            assertTrue(Files.exists(builder.getPath().resolve("schemas/myExpression.expression")));
        });
    }

    @Test
    @SuppressWarnings("try") // application unreferenced inside try
    public void builder_cannot_be_reused() throws Exception {
        ApplicationBuilder builder = new ApplicationBuilder();
        builder.servicesXml("<container version=\"1.0\" />");
        try (Application application = builder.build()) {
            // do nothing
        }

        Exception e = assertThrows(RuntimeException.class, () -> builder.servicesXml(""));
        assertThat(e.getMessage(), containsString("build method"));
    }

    private interface TestCase {
        void accept(ApplicationBuilder ab) throws Exception;
    }

    private static void withApplicationBuilder(TestCase f) throws Exception {
        ApplicationBuilder builder = new ApplicationBuilder();
        try {
            f.accept(builder);
        } finally {
            IOUtils.recursiveDeleteDir(builder.getPath().toFile());
        }
    }
}
