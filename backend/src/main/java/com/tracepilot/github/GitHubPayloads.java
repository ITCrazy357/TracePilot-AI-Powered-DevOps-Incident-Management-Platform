package com.tracepilot.github;

import tools.jackson.databind.JsonNode;

final class GitHubPayloads {
    private GitHubPayloads() {
    }

    static long positiveId(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) {
            throw new IllegalArgumentException("Invalid GitHub ID.");
        }
        return node.asLong();
    }

    static String text(JsonNode node, int maxLength) {
        if (!node.isString()) {
            throw new IllegalArgumentException("Invalid GitHub text field.");
        }
        var value = node.asString();
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("Invalid GitHub text length.");
        }
        return value;
    }

    static GitHubDtos.RepositoryData repository(JsonNode node) {
        var id = positiveId(node.path("id"));
        var fullName = text(node.path("full_name"), 300);
        if (!fullName.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid repository full name.");
        }
        var branch = node.path("default_branch");
        return new GitHubDtos.RepositoryData(id, fullName,
            branch.isMissingNode() || branch.isNull() ? null : text(branch, 255));
    }
}
