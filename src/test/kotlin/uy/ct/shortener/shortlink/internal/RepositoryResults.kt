package uy.ct.shortener.shortlink.internal

import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.InsertResult
import uy.ct.shortener.shortlink.LinkLookup
import uy.ct.shortener.shortlink.LinkStatus
import uy.ct.shortener.shortlink.ShortLink

/** Test shortcuts that unwrap a repository answer, failing the test when it is not the expected one. */
fun InsertResult.created(): ShortLink = (this as InsertResult.Created).link

fun LinkLookup.found(): ShortLink = (this as LinkLookup.Found).link

val ShortLink.disabledBy: Actor get() = (status as LinkStatus.Disabled).by
