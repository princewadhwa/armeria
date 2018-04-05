/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ctrlshift.client.endpoint;

import com.ctrlshift.client.ClientRequestContext;
import com.ctrlshift.client.Endpoint;

/**
 * Selects an {@link Endpoint} from an {@link EndpointGroup}.
 */
public interface EndpointSelector {
    /**
     * Returns the {@link EndpointGroup} held by this selector.
     */
    EndpointGroup group();

    /**
     * Returns the {@link EndpointSelectionStrategy} used by this selector to select an {@link Endpoint}.
     */
    EndpointSelectionStrategy strategy();

    /**
     * Selects an {@link Endpoint} from the {@link EndpointGroup} associated with the specified
     * {@link ClientRequestContext}.
     *
     * @return the {@link Endpoint} selected by this {@link EndpointSelector}'s selection strategy
     */
    Endpoint select(ClientRequestContext ctx);
}