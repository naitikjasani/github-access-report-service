package com.githubaccessreport.report.view;

import java.util.List;

public record PageResult<T>(List<T> items, int number, int size, long totalElements, int totalPages) {

    public static <T> PageResult<T> of(List<T> all, int page, int size) {
        int from = (int) Math.min((long) page * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = (int) Math.ceil(all.size() / (double) size);
        return new PageResult<>(List.copyOf(all.subList(from, to)), page, size, all.size(), totalPages);
    }
}
