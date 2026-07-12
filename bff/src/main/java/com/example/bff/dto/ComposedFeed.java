package com.example.bff.dto;

import java.util.List;

/** Cursor page whose items are composed without sharing downstream databases. */
public record ComposedFeed(List<PostDetail> items, String nextCursor, long sourceVersionWatermark) {
}
