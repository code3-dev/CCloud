package com.pira.ccloud.sync

import com.pira.ccloud.data.model.Country
import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.Genre
import com.pira.ccloud.data.model.Source
import com.pira.ccloud.data.model.sync.MediaSnapshot
import com.pira.ccloud.data.model.sync.SnapshotCountry
import com.pira.ccloud.data.model.sync.SnapshotGenre
import com.pira.ccloud.data.model.sync.SnapshotSource

/** Converts favorites to and from the snapshot format shared with the Apple app. */
object MediaSnapshotMapping {
    fun toSnapshot(item: FavoriteItem) = MediaSnapshot(
        id = item.id,
        kind = item.type,
        title = item.title,
        overview = item.description,
        year = item.year.takeIf { it > 0 },
        imdb = item.imdb.takeIf { it > 0 },
        rating = item.rating.takeIf { it > 0 },
        duration = item.duration,
        poster = item.image.takeIf { it.isNotEmpty() },
        cover = item.cover.takeIf { it.isNotEmpty() },
        genres = item.genres.map { SnapshotGenre(it.id, it.title) },
        countries = item.country.map { SnapshotCountry(it.id, it.title, it.image.takeIf(String::isNotEmpty)) },
        sources = item.sources.map { SnapshotSource(it.id, it.quality, it.type, it.url) }
    )

    fun toFavoriteItem(snapshot: MediaSnapshot) = FavoriteItem(
        id = snapshot.id,
        type = snapshot.kind,
        title = snapshot.title,
        description = snapshot.overview,
        year = snapshot.year ?: 0,
        imdb = snapshot.imdb ?: 0.0,
        rating = snapshot.rating ?: 0.0,
        duration = snapshot.duration,
        image = snapshot.poster.orEmpty(),
        cover = snapshot.cover.orEmpty(),
        genres = snapshot.genres.map { Genre(it.id, it.title) },
        country = snapshot.countries.map { Country(it.id, it.title, it.image.orEmpty()) },
        sources = snapshot.sources.map { Source(it.id, it.quality, it.format, it.url) }
    )
}
