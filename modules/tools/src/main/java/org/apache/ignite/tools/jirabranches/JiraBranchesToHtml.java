/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.tools.jirabranches;

import com.atlassian.jira.rest.client.api.*;
import com.atlassian.jira.rest.client.api.domain.*;
import com.atlassian.jira.rest.client.internal.async.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;

import java.awt.*;
import java.io.*;
import java.lang.ProcessBuilder.*;
import java.net.*;
import java.text.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;

/**
 * Generates html page with the JIRA tickets information.
 */
public class JiraBranchesToHtml {
    /** */
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("MM/dd/yyyy");

    /** Jira url. */
    private String jiraUrl;

    /** Script path. */
    private String scriptPath;

    /** Input file. */
    private String inputFile;

    /** Output file. */
    private String outputFile;

    /** */
    private static final Pattern TICKET_PATTERN = Pattern.compile("\\d{5}|\\d{4}");

    /** */
    private static final Comparator<Result> COMP = new Comparator<Result>() {
        @Override public int compare(Result o1, Result o2) {
            if (o1.issue != null && o2.issue != null) {
                int res = name(o1.issue.getAssignee()).compareTo(name(o2.issue.getAssignee()));

                return res == 0 ? o1.issueKey.compareTo(o2.issueKey) : res;
            }
            else if (o1.issue == null && o2.issue == null)
                return o1.issueKey.compareTo(o2.issueKey);
            else
                return o1.issue == null ? 1 : -1;
        }
    };
    private String prefix;

    /**
     * @param args Arguments.
     * @throws Exception If failed.
     */
    public static void main(String[] args) throws Exception {
        new JiraBranchesToHtml()
            .setJiraUrl("https://issues.apache.org/jira")
            .setPrefix("IGNITE")
            .setScriptPath(U.getIgniteHome() + "/scripts/jira-branches.sh")
            .setInputFile(U.getIgniteHome() + "/scripts/jira-branches.js")
            .setOutputFile(U.getIgniteHome() + "/scripts/jira-branches-results.html")
            .generateReport();
    }

    /**
     * Generate report about git branches and related Jira issues.
     *
     * @throws Exception
     */
    protected void generateReport() throws Exception {
        System.out.println("Need to enter credentials for JIRA [" + jiraUrl + "]");
        System.out.print("JIRA user: ");

        BufferedReader rdr = new BufferedReader(new InputStreamReader(System.in));

        String user = rdr.readLine();

        if (F.isEmpty(user))
            throw new IllegalStateException("JIRA user name cannot be empty.");

        // Mac OS has no Console support (at least in IDE), so have to read password as plain text.
        System.out.print("Password: ");

        String pswd = rdr.readLine();

        if (F.isEmpty(pswd))
            throw new IllegalStateException("JIRA password cannot be empty.");

        System.out.print("Report 'Closed' issues only [y/N]: ");

        boolean closedOnly = "y".equalsIgnoreCase(rdr.readLine());

        System.out.println();
        System.out.println(">>> Executing script: " + scriptPath);
        System.out.println();

        Process proc = new ProcessBuilder(scriptPath)
            .directory(new File(scriptPath).getParentFile())
            .redirectOutput(Redirect.INHERIT)
            .redirectError(Redirect.INHERIT)
            .start();

        proc.waitFor();

        System.out.println();
        System.out.println(">>> Finished executing script [script=" + scriptPath +
            ", exitCode=" + proc.exitValue() + ']');
        System.out.println();

        if (proc.exitValue() != 0)
            throw new Exception("Failed to run script [script=" + scriptPath +
                ", exitCode=" + proc.exitValue() + ']');

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile)));
             JiraRestClient restClient = new AsynchronousJiraRestClientFactory().
                 createWithBasicHttpAuthentication(URI.create(jiraUrl), user, pswd)) {
            List<Result> res = new ArrayList<>();

            for (String line; (line = br.readLine()) != null; ) {
                String branchName = line.replace("\\", "").trim();

                if (branchName.startsWith(prefix)) {
                    Result r = result(restClient, branchName);

                    if (r.error != null) {
                        Matcher m = TICKET_PATTERN.matcher(branchName);

                        if (m.find()) {
                            Result r0 = result(restClient, prefix + "-" + m.group(0));

                            if (r0.error == null)
                                r = new Result(branchName, r0.issue, null);
                        }
                    }

                    if (r.issue == null || !closedOnly || "Closed".equalsIgnoreCase(r.issue.getStatus().getName())) {
                        System.out.println("Added issue: " + r);

                        res.add(r);
                    }
                }
            }

            String s = printIssueDetails(res);

            System.out.println(s);

            try (OutputStreamWriter bw = new OutputStreamWriter(new FileOutputStream(outputFile))) {
                bw.write(s);
            }

            if (Desktop.isDesktopSupported())
                Desktop.getDesktop().open(new File(outputFile));
            else
                System.out.println("Results have been written to: " + outputFile);
        }
    }

    /**
     * @param jiraUrl Jira url.
     */
    public JiraBranchesToHtml setJiraUrl(String jiraUrl) {
        this.jiraUrl = jiraUrl;

        return this;
    }

    /**
     * @param scriptPath Script path.
     */
    public JiraBranchesToHtml setScriptPath(String scriptPath) {
        this.scriptPath = scriptPath;

        return this;
    }

    /**
     * @param inputFile Input file.
     */
    public JiraBranchesToHtml setInputFile(String inputFile) {
        this.inputFile = inputFile;

        return this;
    }

    /**
     * @param outputFile Output file.
     */
    public JiraBranchesToHtml setOutputFile(String outputFile) {
        this.outputFile = outputFile;

        return this;
    }

    /**
     * @param prefix Jira prefix.
     */
    public JiraBranchesToHtml setPrefix(String prefix) {
        this.prefix = prefix;

        return this;
    }

    /**
     * @param restClient Rest client.
     * @param branchName Branch name.
     * @return Result.
     */
    private static Result result(JiraRestClient restClient, String branchName) {
        try {
            Issue issue = restClient.getIssueClient().getIssue(branchName).claim();

            return new Result(branchName, issue, null);
        }
        catch (RestClientException e) {
            return new Result(branchName, null, e.getMessage());
        }
    }

    /**
     * @param res Results.
     * @return Output.
     */
    private String printIssueDetails(List<Result> res) {
        StringBuilder sb = new StringBuilder();

        println(sb, "<html>\n<head></head>\n<body>");
        print(sb, "<table>");

        Collections.sort(res, COMP);

        for (Result r : res) {
            print(sb, "\n<tr>");

            if (r.error != null) {
                print(sb, "<th colspan=7 align=\"left\">" + r.issueKey + " " + r.error + "</th>");
                print(sb, "</tr>");

                continue;
            }

            print(sb, "<th colspan=7 align=\"left\">" +
                "<a href=" + URI.create(jiraUrl) + "/browse/" + r.issue.getKey() + ">" +
                r.issueKey + ' ' + r.issue.getSummary() + "<a></th>");

            print(sb, "</tr><tr>");

            print(sb, "<td><strong>Assignee:</strong> " + name(r.issue.getAssignee()) + "</td>");
            print(sb, "<td><strong>Reporter:</strong> " + name(r.issue.getReporter()) + "</td>");
            print(sb, "<td><strong>Status:</strong> " + r.issue.getStatus().getName() + "</td>");
            print(sb, "<td><strong>Resolution:</strong> " +
                (r.issue.getResolution() == null ? "Unresolved" : r.issue.getResolution().getName()) + "</td>");

            if (r.issue.getFixVersions() != null) {
                print(sb, "<td><strong>Version:</strong>");

                for (Version version : r.issue.getFixVersions())
                    print(sb, " " + version.getName());

                print(sb, "</td>");
            }

            print(sb, "<td><strong>CreationDate:</strong> " + FORMAT.format(r.issue.getCreationDate().toDate()) + "</td>");
            print(sb, "<td><strong>UpdateDate:</strong> " + FORMAT.format(r.issue.getUpdateDate().toDate()) + "</td>");

            print(sb, "</tr><tr><td>  </td></tr>");
        }

        print(sb, "\n</table>");
        print(sb, "\n</body>\n</html>\n");

        return sb.toString();
    }

    /**
     * @param sb StringBuilder.
     * @param s String to print.
     */
    private static void print(StringBuilder sb, String s) {
        sb.append(s);
    }

    /**
     * @param sb StringBuilder.
     * @param s String to print.
     */
    private static void println(StringBuilder sb, String s) {
        sb.append(s).append(System.getProperty("line.separator"));
    }

    /**
     * @param user User.
     * @return Name.
     */
    private static String name(BasicUser user) {
        if (user == null)
            return "";

        return user.getDisplayName() == null ? (user.getName() == null ? "" : user.getName()) : user.getDisplayName();
    }

    /** */
    private static class Result {
        /** */
        private final String issueKey;

        /** */
        private final Issue issue;

        /** */
        private final String error;

        /** */
        Result(String issueKey, Issue issue, String error) {
            this.issueKey = issueKey;
            this.issue = issue;
            this.error = error;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "Result [" +
                "issueKey='" + issueKey + '\'' +
                ", issue=" + (issue == null ? "" : issue.getKey() + " " + issue.getSummary()) +
                ", error='" + error + '\'' +
                ']';
        }
    }
}
