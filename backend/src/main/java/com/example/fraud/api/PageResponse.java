package com.example.fraud.api;

import java.util.List;

public class PageResponse<T> {
    private final List<T> items;
    private final PageInfo page;

    public PageResponse(List<T> items, int number, int size, long totalElements) {
        this.items = items;
        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        this.page = new PageInfo(number, size, totalElements, totalPages);
    }

    public List<T> getItems() { return items; }
    public PageInfo getPage() { return page; }

    public static class PageInfo {
        private final int number;
        private final int size;
        private final long totalElements;
        private final int totalPages;

        public PageInfo(int number, int size, long totalElements, int totalPages) {
            this.number = number;
            this.size = size;
            this.totalElements = totalElements;
            this.totalPages = totalPages;
        }

        public int getNumber() { return number; }
        public int getSize() { return size; }
        public long getTotalElements() { return totalElements; }
        public int getTotalPages() { return totalPages; }
    }
}
