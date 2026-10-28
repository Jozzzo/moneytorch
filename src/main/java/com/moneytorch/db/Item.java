package com.moneytorch.db;

public record Item(String id, String nameEn, String nameRu, String nameZh, String main, String mainLabel,
                   String sub, String subLabel, String tlidbSlug) {
}
