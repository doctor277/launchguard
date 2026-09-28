package io.github.doctor277.launchguard.service;

final class PaginationPolicy {

    static final int MAX_PAGE_SIZE = 100;

    private PaginationPolicy() {
    }

    static void validate(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
