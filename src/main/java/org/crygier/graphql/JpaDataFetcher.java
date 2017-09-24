package org.crygier.graphql;

import graphql.language.*;
import graphql.schema.*;
import java.lang.reflect.Member;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.*;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.PluralAttribute;
import javax.persistence.metamodel.SingularAttribute;
import java.util.*;
import java.util.stream.Collectors;
import javax.persistence.ManyToMany;
import javax.persistence.OneToMany;
import javax.persistence.PersistenceUnitUtil;

public class JpaDataFetcher implements DataFetcher {
    protected EntityType<?> entityType;

    public JpaDataFetcher(EntityType<?> entityType) {
        this.entityType = entityType;
    }

    @Override
    public Object get(DataFetchingEnvironment environment) {
		
		Object result = null;
		
		Field field = environment.getFields().iterator().next();
		
		if (environment.getSource() != null) {
			Object source = environment.getSource();
			
			PersistenceUnitUtil persistenceUnitUtil = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
			
			//this could cause inconsistent behavior in the debugger if the debugger retrieves the attributes
			if (persistenceUnitUtil.isLoaded(source, field.getName())) {
				DataFetcher dataFetcher = new PropertyDataFetcher(field.getName());
				result = dataFetcher.get(environment);
			} else {
				List resultList = getQuery(environment, field).getResultList();
								
				Member member = entityManager.getMetamodel().entity(environment.getSource().getClass()).getAttribute(field.getName()).getJavaMember();
				java.lang.reflect.Field property = (java.lang.reflect.Field) member;

				if (Collection.class.isAssignableFrom(property.getType())) {
					result = resultList;
				} else {
					if (resultList.size() == 1) {
						result = resultList.get(0);
					} else {
						//TODO: this should be an error.
					}
				}
			}
			
		} else {
			result = getQuery(environment, field).getResultList();
		}
		
        return result;
    }

    protected TypedQuery getQuery(DataFetchingEnvironment environment, Field field) {
    	EntityManager entityManager = ((EntityManager)environment.getContext());
    	
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object> query = cb.createQuery((Class) entityType.getJavaType());
        Root root = query.from(entityType);
		
		//process the order by for the root before we process any children in getQueryHelper
        processOrderBy(field, root, query, cb);
		
		//recurse through the child fields
		getQueryHelper(environment, field, cb, query, root, root, true);

		List<Predicate> predicates = new ArrayList<>();

		//arguments to the top-level field
		predicates.addAll(field.getArguments().stream()
				.filter(it -> (!"orderBy".equals(it.getName()) && !"joinType".equals(it.getName())))
				.map(it -> getPredicate(cb, getRootArgumentPath(root, it), environment, it))
				.collect(Collectors.toList()));

		//if there is a source, this is a nested query, we need to apply the filtering from the parent
		if (environment.getSource() != null) {
			Predicate predicate = getPredicateForParent(environment, cb, root, query);
		
			if (predicate != null) {
				predicates.add(predicate);
			}
		}
		
        query.where(predicates.toArray(new Predicate[predicates.size()]));
		
        return entityManager.createQuery(query.distinct(true));
    }
	
	protected void getQueryHelper(DataFetchingEnvironment environment, Field field, 
			CriteriaBuilder cb, CriteriaQuery<Object> query, From from, Path path, boolean parentFetched) {
		
		// Loop through all of the fields being requested
        field.getSelectionSet().getSelections().forEach(selection -> {
            if (selection instanceof Field) {
                Field selectedField = (Field) selection;

                // "__typename" is part of the graphql introspection spec and has to be ignored by jpa
                if(!"__typename".equals(selectedField.getName())) {

                    Path fieldPath = path.get(selectedField.getName());

					//make left joins the default
					JoinType joinType = JoinType.LEFT;
					
					Optional<Argument> joinTypeArgument = selectedField.getArguments().stream().filter(it -> "joinType".equals(it.getName())).findFirst();
                    if (joinTypeArgument.isPresent()) {
						joinType = JoinType.valueOf(((EnumValue) joinTypeArgument.get().getValue()).getName());
					}
					
					List<Argument> arguments = selectedField.getArguments().stream()
								.filter(it -> (!"orderBy".equals(it.getName()) && !"joinType".equals(it.getName())))
								.map(it -> new Argument(it.getName(), it.getValue()))
								.collect(Collectors.toList());
					
					boolean fetched = false;
					Join join = null;
					
                    // Check if it's an object and the foreign side is One.  Then we can eagerly fetch causing an inner join instead of 2 queries
                    if (fieldPath.getModel() instanceof SingularAttribute) {
                        SingularAttribute attribute = (SingularAttribute) fieldPath.getModel();
                        if (attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.MANY_TO_ONE || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ONE_TO_ONE) {
                            //we can eagerly fetch TO_ONE associations assuming that the parent was also eagerly fetched
							//hibernate doesn't allow fetches with 'with-clauses' so if there are arguments, we can't fetch
							if (parentFetched && arguments.size() == 0) {
								join = (Join) from.fetch(selectedField.getName(), joinType);
								fetched = true;
							} else {
								join = from.join(selectedField.getName(), joinType);
							}
						}
						
                    } else { //Otherwise, assume the foreign side is many
						if (selectedField.getSelectionSet() != null) {
							//Fetch fetch = from.fetch(selectedField.getName(), joinType);
							//TODO: if we fetch, update the boolean
							join = from.join(selectedField.getName(), joinType);
						}
					}
					
					//Let's assume that we can eventually figure out when to fetch (taking nesting into account) and when not to
					if (fetched) {
						//it's safe to process the ordering for this instances children
						processOrderBy(selectedField, join, query, cb);
					}
					
					if (join != null) {
						final Join forLambda = (Join) join;
						
						getQueryHelper(environment, selectedField, cb, query, ((From)forLambda), ((Join) forLambda), fetched);
						
						List<Predicate> joinPredicates = arguments.stream().map(
								it -> getPredicate(cb, ((Join) forLambda).get(it.getName()), environment, it)).collect(Collectors.toList()
							);
												
						// don't blow away an existing condition
						if (forLambda.getOn() != null) {
							joinPredicates.add(forLambda.getOn());
						}
						
						//add the predicates to the on to faciliate outer joins
						forLambda.on(joinPredicates.toArray(EMPTY_PREDICATES));
					}
                }
            }
        });
		
	}

	private Predicate getPredicate(CriteriaBuilder cb, Path path, DataFetchingEnvironment environment, Argument argument) {
            
			//this must be an object in case an enum is returned
			Object value = convertValue(environment, argument, argument.getValue());
			
			if (value instanceof List) {
				return path.in(value);
			} else {
				return cb.equal(path, value);
			}
    }

    protected Object convertValue(DataFetchingEnvironment environment, Argument argument, Value value) {
        if (value instanceof StringValue) {
			Object convertedValue = null;
			//TODO: The UUID behavior causes issues if you attempt to parse further down the hierarchy
			if (environment.getArguments() != null && environment.getArguments().containsKey(argument.getName())) {
				 convertedValue = environment.getArgument(argument.getName()); //This only works when dealing with the top level object
			}
		   
            if (convertedValue != null && convertedValue instanceof UUID) { 
                // Return real parameter for instance UUID even if the Value is a StringValue
                return convertedValue;
            } else if (convertedValue != null && convertedValue instanceof List) {
				List convertedValueList = (List) convertedValue;
				
				//TODO: this won't properly handle queries on multiple UUIDs
				if (!convertedValueList.isEmpty()) {
					convertedValue = convertedValueList.get(0);
					if (convertedValue instanceof UUID) {
						return convertedValue;
					}
				}
			} 
			
			// Return provided StringValue
			return ((StringValue) value).getValue();
        }
        else if (value instanceof VariableReference)
            return environment.getArguments().get(((VariableReference) value).getName());
        else if (value instanceof ArrayValue)
            return ((ArrayValue) value).getValues().stream().map((it) -> convertValue(environment, argument, it)).collect(Collectors.toList());
        else if (value instanceof EnumValue) {
			//TODO: why does this rely on the environment - This likely doesn't work for nested values
            Class enumType = getJavaType(environment, argument);
            return Enum.valueOf(enumType, ((EnumValue) value).getName());
        } else if (value instanceof IntValue) {
            return ((IntValue) value).getValue();
        } else if (value instanceof BooleanValue) {
            return ((BooleanValue) value).isValue();
        } else if (value instanceof FloatValue) {
            return ((FloatValue) value).getValue();
        }

        return value.toString();
    }

    private Class getJavaType(DataFetchingEnvironment environment, Argument argument) {
        Attribute argumentEntityAttribute = getAttribute(environment, argument);

        if (argumentEntityAttribute instanceof PluralAttribute)
            return ((PluralAttribute) argumentEntityAttribute).getElementType().getJavaType();

        return argumentEntityAttribute.getJavaType();
    }

    private Attribute getAttribute(DataFetchingEnvironment environment, Argument argument) {
    	EntityManager entityManager = ((EntityManager)environment.getContext());
        GraphQLObjectType objectType = getObjectType(environment, argument);
        EntityType entityType = getEntityType(entityManager, objectType);

        return entityType.getAttribute(argument.getName());
    }

    private EntityType getEntityType(EntityManager entityManager, GraphQLObjectType objectType) {
        return entityManager.getMetamodel().getEntities().stream().filter(it -> it.getName().equals(objectType.getName())).findFirst().get();
    }

    private GraphQLObjectType getObjectType(DataFetchingEnvironment environment, Argument argument) {
        GraphQLType outputType = environment.getFieldType();
        if (outputType instanceof GraphQLList)
            outputType = ((GraphQLList) outputType).getWrappedType();

        if (outputType instanceof GraphQLObjectType)
            return (GraphQLObjectType) outputType;

        return null;
    }

	private Predicate getPredicateForParent(DataFetchingEnvironment environment, CriteriaBuilder cb, Root root, CriteriaQuery query) {
		
		Predicate result = null;
		
		//get the source, this will be used to filter the query
		Member member = entityManager.getMetamodel().entity(environment.getSource().getClass()).getAttribute(environment.getFields().get(0).getName()).getJavaMember();

		//TODO: this might need criteria for method as javaField.getAnnotation(OneToMany.class);
		if (member instanceof java.lang.reflect.Field) {
			java.lang.reflect.Field javaField = (java.lang.reflect.Field) member;

			OneToMany oneToMany = javaField.getAnnotation(OneToMany.class);

			if (oneToMany != null) {
				String mappedBy = oneToMany.mappedBy();
				result = cb.equal(root.get(mappedBy), cb.literal(environment.getSource()));
			} else {
				ManyToMany manyToMany = javaField.getAnnotation(ManyToMany.class);
				
				if (manyToMany != null) {
					/* Since the @ManyToMany only needs to be defined one side we can't assume that this side has a clean mapping
					 * back to the parent.  The tests provide a good example of this (C-3PO is not one of Han's friends, even though
					 * C-3PO considers Han a friend.
					 *
					 * This link provides guidance: https://stackoverflow.com/questions/4483576/jpa-2-0-criteria-api-subqueries-in-expressions
					 */
					Subquery subQuery = query.subquery(root.getJavaType());
					Root subQueryRoot = subQuery.from(environment.getSource().getClass());
					subQuery.where(cb.equal(subQueryRoot, cb.literal(environment.getSource())));
					Join subQueryJoin = subQueryRoot.join(environment.getFields().get(0).getName());
					subQuery.select(subQueryJoin);
					result = root.in(subQuery);
				}
			}
		}

		return result;
	}

	private void processOrderBy(Field field, Path path, CriteriaQuery<Object> query, CriteriaBuilder cb) {
		// Loop through this fields selections and apply the ordering
		field.getSelectionSet().getSelections().forEach(selection -> {
			if (selection instanceof Field) {
				Field selectedField = (Field) selection;
				
				// "__typename" is part of the graphql introspection spec and has to be ignored by jpa
				if(!"__typename".equals(selectedField.getName())) {
					
					Path fieldPath = path.get(selectedField.getName());
					
					// Process the orderBy clause - orderBy can only be processed for fields actually being returned, but this should be smart enough to account for fetches
					Optional<Argument> orderByArgument = selectedField.getArguments().stream().filter(it -> "orderBy".equals(it.getName())).findFirst();
					if (orderByArgument.isPresent()) {
						
						List<Order> orders = new ArrayList<>();
						
						//add the previous ordering first so that it is retained
						if (query.getOrderList() != null) {
							orders.addAll(query.getOrderList());
						}
						
						if ("DESC".equals(((EnumValue) orderByArgument.get().getValue()).getName())) {
							orders.add(cb.desc(fieldPath));
						} else {
							orders.add(cb.asc(fieldPath));
						}
						
						query.orderBy(orders);
					}
				}
			}
		});
	}

	private Path getRootArgumentPath(Root root, Argument it) {
		//This only needs to the join case when we're querying by an ENUM, otherwise the arguments will be "primitive" values
		return root.get(it.getName()).getModel() instanceof SingularAttribute ? root.get(it.getName()): root.join(it.getName(), JoinType.LEFT);
	}
	
	private static final Predicate[] EMPTY_PREDICATES = {};
}