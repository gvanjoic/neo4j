/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.io.pagecache.stress;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;

import org.neo4j.io.pagecache.PageCursor;

public class RecordVerifierUpdater
{
    private static final int SizeOfLong = 8;

    private final int numberOfCounters;

    public RecordVerifierUpdater( int numberOfCounters )
    {
        this.numberOfCounters = numberOfCounters;
    }

    // under exclusive lock: no retries necessary
    public void verifyChecksumAndUpdateCount( PageCursor cursor, int recordNumber, int counterNumber ) throws IOException
    {
        verifyChecksum( cursor, recordNumber );

        updateCount( cursor, recordNumber, counterNumber );
    }

    // under shared lock: remember to retry
    public void verifyCount( PageCursor cursor, int recordNumber, int counterNumber, long expectedCount ) throws IOException
    {
        long actualCount;
        do
        {
            cursor.setOffset( recordNumber * getRecordSize() + counterNumber * SizeOfLong );

            actualCount = cursor.getLong();
        } while ( cursor.shouldRetry() );

        assertThat( actualCount, is( expectedCount ) );
    }

    // under shared lock or exclusive lock: must retry
    public void verifyChecksum( PageCursor cursor, int recordNumber ) throws IOException
    {
        long actualChecksum = 0;
        long checksum;
        do
        {
            cursor.setOffset( recordNumber * getRecordSize() );

            for ( int i = 0; i < numberOfCounters; i++ )
            {
                long count = cursor.getLong();

                actualChecksum += count;
            }

            checksum = cursor.getLong();
        } while ( cursor.shouldRetry() );

        assertThat( actualChecksum, is( checksum ) );
    }

    public int getRecordSize()
    {
        return (numberOfCounters + 1) * SizeOfLong;
    }

    private void updateCount( PageCursor cursor, int recordNumber, int counterNumber )
    {
        cursor.setOffset( recordNumber * getRecordSize() + counterNumber * SizeOfLong );
        long count = cursor.getLong();
        cursor.setOffset( recordNumber * getRecordSize() + counterNumber * SizeOfLong );
        cursor.putLong( count + 1 );
        cursor.setOffset( recordNumber * getRecordSize() + numberOfCounters * SizeOfLong );
        long checksum = cursor.getLong();
        cursor.setOffset( recordNumber * getRecordSize() + numberOfCounters * SizeOfLong );
        cursor.putLong( checksum + 1 );
    }
}
