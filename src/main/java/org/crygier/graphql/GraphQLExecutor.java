package org.crygier.graphql;

import java.util.Map;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.persistence.EntityManager;
import javax.transaction.Transactional;

import graphql.ExecutionResult;
import graphql.GraphQL;

public class GraphQLExecutor {

    @Resource
    private EntityManager entityManager;
    private GraphQL graphQL;

    protected GraphQLExecutor() {}
    
    public GraphQLExecutor(EntityManager entityManager) {
        this.entityManager = entityManager;
        createGraphQL();
    }

    @PostConstruct
    protected void createGraphQL() {
        if (entityManager != null) {
            this.graphQL = GraphQL.newGraphQL(new GraphQLSchemaBuilder(entityManager.getMetamodel()).getGraphQLSchema()).build();
        }
    }

    @Transactional
    public ExecutionResult execute(String query) {
        return graphQL.execute(query);
    }

    @Transactional
	    public ExecutionResult execute(String query, Map<String, Object> arguments) {
	    	return graphQL.execute(query, entityManager, arguments);
    }

}
