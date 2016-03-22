/**
 * This file is part of Graylog Pipeline Processor.
 *
 * Graylog Pipeline Processor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog Pipeline Processor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog Pipeline Processor.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog.plugins.pipelineprocessor.rest;

import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.graylog.plugins.pipelineprocessor.ast.Pipeline;
import org.graylog.plugins.pipelineprocessor.db.PipelineDao;
import org.graylog.plugins.pipelineprocessor.db.PipelineService;
import org.graylog.plugins.pipelineprocessor.events.PipelinesChangedEvent;
import org.graylog.plugins.pipelineprocessor.parser.ParseException;
import org.graylog.plugins.pipelineprocessor.parser.PipelineRuleParser;
import org.graylog2.database.NotFoundException;
import org.graylog2.events.ClusterEventBus;
import org.graylog2.plugin.rest.PluginRestResource;
import org.graylog2.shared.rest.resources.RestResource;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collection;

@Api(value = "Pipelines/Pipelines", description = "Pipelines for the pipeline message processor")
@Path("/system/pipelines/pipeline")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PipelineResource extends RestResource implements PluginRestResource {

    private static final Logger log = LoggerFactory.getLogger(PipelineResource.class);

    private final PipelineService pipelineService;
    private final PipelineRuleParser pipelineRuleParser;
    private final EventBus clusterBus;

    @Inject
    public PipelineResource(PipelineService pipelineService,
                        PipelineRuleParser pipelineRuleParser,
                        ClusterEventBus clusterBus) {
        this.pipelineService = pipelineService;
        this.pipelineRuleParser = pipelineRuleParser;
        this.clusterBus = clusterBus;
    }

    @ApiOperation(value = "Create a processing pipeline from source", notes = "")
    @POST
    @Path("/")
    public PipelineSource createFromParser(@ApiParam(name = "pipeline", required = true) @NotNull PipelineSource pipelineSource) throws ParseException {
        final Pipeline pipeline;
        try {
            pipeline = pipelineRuleParser.parsePipeline(pipelineSource.id(), pipelineSource.source());
        } catch (ParseException e) {
            throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST).entity(e.getErrors()).build());
        }
        final PipelineDao pipelineDao = PipelineDao.builder()
                .title(pipeline.name())
                .description(pipelineSource.description())
                .source(pipelineSource.source())
                .createdAt(DateTime.now())
                .modifiedAt(DateTime.now())
                .build();
        final PipelineDao save = pipelineService.save(pipelineDao);
        clusterBus.post(PipelinesChangedEvent.updatedPipelineId(save.id()));
        log.debug("Created new pipeline {}", save);
        return PipelineSource.fromDao(pipelineRuleParser, save);
    }

    @ApiOperation(value = "Parse a processing pipeline without saving it", notes = "")
    @POST
    @Path("/parse")
    public PipelineSource parse(@ApiParam(name = "pipeline", required = true) @NotNull PipelineSource pipelineSource) throws ParseException {
        final Pipeline pipeline;
        try {
            pipeline = pipelineRuleParser.parsePipeline(pipelineSource.id(), pipelineSource.source());
        } catch (ParseException e) {
            throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST).entity(e.getErrors()).build());
        }
        return PipelineSource.builder()
                .title(pipeline.name())
                .description(pipelineSource.description())
                .source(pipelineSource.source())
                .createdAt(DateTime.now())
                .modifiedAt(DateTime.now())
                .build();
    }

    @ApiOperation(value = "Get all processing pipelines")
    @GET
    @Path("/")
    public Collection<PipelineSource> getAll() {
        final Collection<PipelineDao> daos = pipelineService.loadAll();
        final ArrayList<PipelineSource> results = Lists.newArrayList();
        for (PipelineDao dao : daos) {
            results.add(PipelineSource.fromDao(pipelineRuleParser, dao));
        }

        return results;
    }

    @ApiOperation(value = "Get a processing pipeline", notes = "It can take up to a second until the change is applied")
    @Path("/{id}")
    @GET
    public PipelineSource get(@ApiParam(name = "id") @PathParam("id") String id) throws NotFoundException {
        final PipelineDao dao = pipelineService.load(id);
        return PipelineSource.fromDao(pipelineRuleParser, dao);
    }

    @ApiOperation(value = "Modify a processing pipeline", notes = "It can take up to a second until the change is applied")
    @Path("/{id}")
    @PUT
    public PipelineSource update(@ApiParam(name = "id") @PathParam("id") String id,
                             @ApiParam(name = "pipeline", required = true) @NotNull PipelineSource update) throws NotFoundException {
        final PipelineDao dao = pipelineService.load(id);
        final Pipeline pipeline;
        try {
            pipeline = pipelineRuleParser.parsePipeline(update.id(), update.source());
        } catch (ParseException e) {
            throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST).entity(e.getErrors()).build());
        }
        final PipelineDao toSave = dao.toBuilder()
                .title(pipeline.name())
                .description(update.description())
                .source(update.source())
                .modifiedAt(DateTime.now())
                .build();
        final PipelineDao savedPipeline = pipelineService.save(toSave);
        clusterBus.post(PipelinesChangedEvent.updatedPipelineId(savedPipeline.id()));

        return PipelineSource.fromDao(pipelineRuleParser, savedPipeline);
    }

    @ApiOperation(value = "Delete a processing pipeline", notes = "It can take up to a second until the change is applied")
    @Path("/{id}")
    @DELETE
    public void delete(@ApiParam(name = "id") @PathParam("id") String id) throws NotFoundException {
        pipelineService.load(id);
        pipelineService.delete(id);
        clusterBus.post(PipelinesChangedEvent.deletedPipelineId(id));
    }

}
