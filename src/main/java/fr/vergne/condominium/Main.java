package fr.vergne.condominium;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.find;
import static java.nio.file.Files.isRegularFile;
import static java.util.stream.Collectors.joining;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.DumperOptions.LineBreak;
import org.yaml.snakeyaml.DumperOptions.ScalarStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

import fr.vergne.condominium.Main.IssueData.ItemData;
import fr.vergne.condominium.Main.IssueData.ItemData.SourceData;
import fr.vergne.condominium.core.diagram.Diagram;
import fr.vergne.condominium.core.history.MailHistory;
import fr.vergne.condominium.core.issue.Issue;
import fr.vergne.condominium.core.issue.Issue.History;
import fr.vergne.condominium.core.mail.Header;
import fr.vergne.condominium.core.mail.Mail;
import fr.vergne.condominium.core.mail.Mail.Body;
import fr.vergne.condominium.core.mail.MimeType;
import fr.vergne.condominium.core.mail.SoftReferencedMail;
import fr.vergne.condominium.core.parser.mbox.MBoxParser;
import fr.vergne.condominium.core.parser.yaml.MailCleaningConfiguration;
import fr.vergne.condominium.core.parser.yaml.PlotConfiguration;
import fr.vergne.condominium.core.parser.yaml.ProfilesConfiguration;
import fr.vergne.condominium.core.repository.FileRepository;
import fr.vergne.condominium.core.repository.MemoryRepository;
import fr.vergne.condominium.core.repository.Repository;
import fr.vergne.condominium.core.repository.RepositoryDiff;
import fr.vergne.condominium.core.repository.RepositoryDiff.ResourceDiff.Action;
import fr.vergne.condominium.core.repository.RepositoryDiff.ResourceDiff.Values;
import fr.vergne.condominium.core.source.Source;
import fr.vergne.condominium.core.source.Source.Refiner;

public class Main {
	private static final Consumer<Object> LOGGER = System.out::println;

	public static class Y {
		private String name;
		private Object value;

		static class Root extends Y {
			public Root(String id) {
				super("root", id);
			}
		}

		public Y() {
		}

		public Y(String name, Object value) {
			this.name = name;
			this.value = value;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Object getValue() {
			return value;
		}

		public void setValue(Object value) {
			this.value = value;
		}
	}

	public static void main(String[] args) throws IOException {
		Path importFolderPath = Paths.get(System.getProperty("importFolder"));
		Path confFolderPath = Paths.get(System.getProperty("confFolder"));
		Path outFolderPath = Paths.get(System.getProperty("outFolder"));
		outFolderPath.toFile().mkdirs();
		Path mboxPath = importFolderPath.resolve(System.getProperty("mbox"));
		Path confProfilesPath = confFolderPath.resolve("profiles.yaml");
		Path confMailCleaningPath = confFolderPath.resolve("mailCleaning.yaml");
		Path confPlotCsPath = confFolderPath.resolve("plotCs.yaml");
		Path confPlotSyndicPath = confFolderPath.resolve("plotSyndic.yaml");
		Path historyScriptPath = outFolderPath.resolve("graph.plantuml");
		Path historyPath = outFolderPath.resolve("graph.svg");
		Path plotCsPath = outFolderPath.resolve("graph2.png");
		Path plotSyndicPath = outFolderPath.resolve("graph3.png");
		Path mailRepositoryPath = outFolderPath.resolve("mails");
		Path issueRepositoryPath = outFolderPath.resolve("issues");

		Repository<Mail, MailId> mailRepository = createMailRepository(mailRepositoryPath);

		LOGGER.accept("--- UPDATE ---");
//		updateMailsExceptRemovals(loadMBox(mboxPath, confMailCleaningPath), mailRepository);
		LOGGER.accept("--- /UPDATE ---");

		Source<Repository<Mail, MailId>> repoSource = Source.create(mailRepository);
		Source.Refiner<Repository<Mail, MailId>, MailId, Mail> mailRefiner = Source.Refiner
				.create(Repository<Mail, MailId>::mustGet);

		// TODO Extract to dedicated object
		// TODO Test
		Map<String, Source<?>> roots = new HashMap<>();
		WeakHashMap<Source<?>, List<Y>> identifierCache = new WeakHashMap<>();
		Function<Source<?>, List<Y>> rejectMissingIdentifier = source -> {
			throw new NoSuchElementException("No identifier for source: " + source);
		};
		roots.put("mails", repoSource);
		identifierCache.put(repoSource, List.of(new Y.Root("mails")));
		Map<String, Source.Refiner<?, ?, ?>> refiners = new HashMap<>();
		Map<String, Function<Map<String, String>, ?>> deserializers = new HashMap<>();
		{
			DateTimeFormatter dateParser = DateTimeFormatter.ISO_DATE_TIME;
			Function<MailId, Map<String, String>> idSerializer = id -> {
				HashMap<String, String> map = new HashMap<>();
				String dateTime = dateParser.format(id.datetime());
				map.put("dateTime", dateTime);
				String email = id.sender();
				map.put("email", email);
				return map;
			};
			Function<Map<String, String>, MailId> idDeserializer = map -> {
				ZonedDateTime date = ZonedDateTime.from(dateParser.parse(map.get("dateTime")));
				String email = map.get("email");
				return new MailId(date, email);
			};
			deserializers.put("id", idDeserializer);
			{
				Refiner<Repository<Mail, MailId>, MailId, Mail> parentRefiner = mailRefiner;
				mailRefiner = new Source.Refiner<Repository<Mail, MailId>, MailId, Mail>() {

					@Override
					public Source<Mail> resolve(Source<Repository<Mail, MailId>> parentSource, MailId id) {
						Source<Mail> resolved = parentRefiner.resolve(parentSource, id);
						List<Y> parentId = identifierCache.computeIfAbsent(parentSource, rejectMissingIdentifier);
						Y childId = new Y("id", idSerializer.apply(id));
						List<Y> fullId = new ArrayList<>(parentId.size() + 1);
						fullId.addAll(parentId);
						fullId.add(childId);
						identifierCache.put(resolved, fullId);
						return resolved;
					}
				};
			}
			refiners.put("id", mailRefiner);
		}
		Function<Source<?>, List<Y>> sourceIdentifier = source -> {
			return identifierCache.computeIfAbsent(source, rejectMissingIdentifier);
		};
		Function<List<Y>, Source<?>> sourceResolver = id -> {
			Y rootChunk = id.get(0);
			Object rootKey = rootChunk.getValue();
			Source<?> source = roots.get(rootKey);

			return id.stream().skip(1)//
					.map(chunk -> {
						String refinerKey = chunk.getName();
						Refiner<?, ?, ?> refiner = refiners.get(refinerKey);
						Object refinerId = chunk.getValue();
						Function<Map<String, String>, ?> deserializer = deserializers.get(refinerKey);
						@SuppressWarnings("unchecked")
						Object idObject = deserializer.apply((Map<String, String>) refinerId);
						UnaryOperator<Source<?>> refinement = parentSource -> resolveHelper(refiner, idObject,
								parentSource);
						return refinement;
					}).<Source<?>>reduce(source, (s, op) -> op.apply(s), (s1, s2) -> {
						throw new RuntimeException("Not implemented");
					});
		};

		{
			Repository<Mail, MailId> repo = repoSource.resolve();
			Source<?> source = repoSource;
			List<Y> id = sourceIdentifier.apply(source);
			System.out.println("root=mails = " + id.equals("root=mails"));
			Source<?> retrieved = sourceResolver.apply(id);
			System.out.println("source = " + source.equals(retrieved));
			Object resolved = retrieved.resolve();
			System.out.println("object = " + repo.equals(resolved));
		}

		MailId mailId = mailRepository.streamKeys().findFirst().orElseThrow();
		Source<Mail> mailSource = repoSource.refine(mailRefiner, mailId);
		{
			Mail mail = mailSource.resolve();
			Source<?> source = mailSource;
			List<Y> id = sourceIdentifier.apply(source);
			System.out.println("root=mailsµid=xxx = " + id.equals(
					"root=mailsµid=L'équipe Google Community¤googlecommunityteam-noreply@google.com¤2023-01-20T08:57:35+01:00[Europe/Paris]"));
			Source<?> retrieved = sourceResolver.apply(id);
			System.out.println("source = " + source.equals(retrieved));
			Object resolved = retrieved.resolve();
			System.out.println("object = " + mail.equals(resolved));
		}

		Repository<Issue, IssueId> issueRepository = createIssueRepository(issueRepositoryPath, sourceIdentifier,
				sourceResolver);
		LOGGER.accept("=================");
		List<Mail> mails = mailRepository.streamResources().toList();
		{
			LOGGER.accept("Associate mails to issues");
			// TODO Retrieve mail from ID
			// TODO Update issue status from email
			// TODO Notify with email section
			// TODO Issue repository
			LOGGER.accept("**********************");
			Mail mail = mailSource.resolve();
			LOGGER.accept("From: " + mail.sender());
			LOGGER.accept("To: " + mail.receivers().toList());
			LOGGER.accept("At: " + mail.receivedDate());
			LOGGER.accept("Subject: " + mail.subject());
			LOGGER.accept("Body:");
			LOGGER.accept(reduceToPlainOrHtmlBody(mail).text());
			if (issueRepository.stream().count() == 0) {
				Issue issue = Issue.create("Panne de chauffage", mail.receivedDate());
				issue.notify(Issue.Status.REPORTED, mailSource, mail.receivedDate());
				IssueId issueId = issueRepository.add(issue);
				LOGGER.accept("Added: " + issueId);
			} else {
				IssueId issueId = issueRepository.streamKeys().findFirst().get();
				LOGGER.accept("Retrieving: " + issueId);
				Issue issue = issueRepository.mustGet(issueId);
				LOGGER.accept("Issue: " + issue);
				issue.history().stream().forEach(item -> {
					LOGGER.accept("- " + item);
					LOGGER.accept("> " + item.source().resolve());
				});
				// TODO Update repository
			}
		}

		String x;
		if ("".length() == 0)
			throw new RuntimeException("Check");

		LOGGER.accept("=================");

		LOGGER.accept("Read profiles conf");
		ProfilesConfiguration confProfiles = ProfilesConfiguration.parser().apply(confProfilesPath);

		LOGGER.accept("=================");
		{
			// TODO Filter on mail predicate
			LOGGER.accept("Create mail history");
			MailHistory.Factory mailHistoryFactory = new MailHistory.Factory.WithPlantUml(confProfiles, LOGGER);
			MailHistory mailHistory = mailHistoryFactory.create(mails);
			mailHistory.writeScript(historyScriptPath);
			mailHistory.writeSvg(historyPath);
			LOGGER.accept("Done");
		}

		LOGGER.accept("=================");
		int diagramWidth = 1000;
		int diagramHeightPerPlot = 250;
		Diagram.Factory diagramFactory = new Diagram.Factory.WithJFreeChart(confProfiles.getGroups());
		{
			LOGGER.accept("Read CS plot conf");
			PlotConfiguration confPlotCs = PlotConfiguration.parser().apply(confPlotCsPath);
			LOGGER.accept("Create plot");
			Diagram diagram = diagramFactory.ofSendReceive(confPlotCs).createDiagram(mails);
			diagram.writePng(plotCsPath, diagramWidth, diagramHeightPerPlot);
			LOGGER.accept("Done");
		}
		{
			LOGGER.accept("Read syndic plot conf");
			PlotConfiguration confPlotSyndic = PlotConfiguration.parser().apply(confPlotSyndicPath);
			LOGGER.accept("Create plot");
			Diagram diagram = diagramFactory.ofSendReceive(confPlotSyndic).createDiagram(mails);
			diagram.writePng(plotSyndicPath, diagramWidth, diagramHeightPerPlot);
			LOGGER.accept("Done");
		}
	}

	@SuppressWarnings("unchecked")
	private static <T1, I, T2> Source<T2> resolveHelper(Refiner<T1, I, T2> refiner, Object idObject,
			Source<?> parentSource) {
		return refiner.resolve((Source<T1>) parentSource, (I) idObject);
	}

	public static class IssueData {
		private String title;
		private String dateTime;
		private List<ItemData> history;

		public static class ItemData {
			private String dateTime;
			private String status;
			private SourceData source;

			public static class SourceData {
				private final List<Y> list;

				public SourceData(List<Y> list) {
					this.list = list;
				}
			}

			public String getDateTime() {
				return dateTime;
			}

			public void setDateTime(String dateTime) {
				this.dateTime = dateTime;
			}

			public String getStatus() {
				return status;
			}

			public void setStatus(String status) {
				this.status = status;
			}

			public SourceData getSource() {
				return source;
			}

			public void setSource(SourceData source) {
				this.source = source;
			}
		}

		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = title;
		}

		public String getDateTime() {
			return dateTime;
		}

		public void setDateTime(String dateTime) {
			this.dateTime = dateTime;
		}

		public List<ItemData> getHistory() {
			return history;
		}

		public void setHistory(List<ItemData> history) {
			this.history = history;
		}
	}

	private static <T> Repository<Issue, IssueId> createIssueRepository(Path repositoryPath,
			Function<Source<?>, List<Y>> sourceSerializer, Function<List<Y>, Source<?>> sourceDeserializer) {
		Function<Issue, IssueId> identifier = issue -> new IssueId(issue);

		// TODO Compose Issue YAML parser with Source YAML parser
		DumperOptions dumpOptions = new DumperOptions();
		dumpOptions.setLineBreak(LineBreak.UNIX);
		dumpOptions.setSplitLines(false);
		dumpOptions.setDefaultFlowStyle(FlowStyle.BLOCK);
		Representer representer = new Representer(dumpOptions) {
			{
				representers.put(IssueData.class, new Represent() {
					@Override
					public Node representData(Object data) {
						LOGGER.accept("<<< ISSUE");
						IssueData issue = (IssueData) data;
						List<NodeTuple> issueTuples = new LinkedList<>();

						{
							Property valueProperty = getPropertyUtils().getProperty(issue.getClass(), "title");
							issueTuples.add(representJavaBeanProperty(issue, valueProperty, issue.title, null));
						}

						{
							Property valueProperty = getPropertyUtils().getProperty(issue.getClass(), "dateTime");
							issueTuples.add(representJavaBeanProperty(issue, valueProperty, issue.dateTime, null));
						}

						List<Node> itemNodes = issue.history.stream().map(item -> {
							List<NodeTuple> itemTuples = new LinkedList<>();

							Tag itemTag = new Tag("!" + item.status.toLowerCase());

							{
								Property valueProperty = getPropertyUtils().getProperty(item.getClass(), "dateTime");
								itemTuples.add(representJavaBeanProperty(item, valueProperty, item.dateTime, null));
							}

							{
								SourceData source = item.source;
								List<Y> list = source.list;
								Y root = list.get(0);
								Node rootNode = representScalar(Tag.STR, (String) root.getValue());
								List<Node> nodes = new LinkedList<>();
								nodes.add(rootNode);
								list.stream().skip(1).forEach(y -> {
									Property valueProperty = getProperties(y.getClass()).stream()
											.filter(p -> p.getName().equals("value")).findFirst().orElseThrow();
									Node node = representJavaBeanProperty(y, valueProperty, y.getValue(), null)
											.getValueNode();
									node.setTag(new Tag("!" + y.getName()));
									nodes.add(node);
								});
								itemTuples.add(new NodeTuple(//
										new ScalarNode(Tag.STR, "source", null, null, ScalarStyle.PLAIN), //
										new SequenceNode(Tag.SEQ, nodes, defaultFlowStyle)//
								));
							}

							return (Node) new MappingNode(itemTag, itemTuples, defaultFlowStyle);
						}).toList();
						issueTuples.add(new NodeTuple(//
								new ScalarNode(Tag.STR, "history", null, null, ScalarStyle.PLAIN), //
								new SequenceNode(Tag.SEQ, itemNodes, defaultFlowStyle)//
						));

						return new MappingNode(Tag.MAP, issueTuples, defaultFlowStyle);
					}
				});
			}
		};
		LoaderOptions loaderOptions = new LoaderOptions();
		loaderOptions.setTagInspector(tag -> true);
		Constructor constructor = new Constructor(IssueData.class, loaderOptions) {
			{
				yamlConstructors.put(null, new AbstractConstruct() {

					@Override
					public Object construct(Node node) {
						MappingNode issueNode = (MappingNode) node;
						IssueData issueData = new IssueData();
						issueNode.getValue().forEach(tuple -> {
							String tupleKey = ((ScalarNode) tuple.getKeyNode()).getValue();
							if (tupleKey.equals("title")) {
								issueData.title = extractScalarValue(tuple);
							} else if (tupleKey.equals("dateTime")) {
								issueData.dateTime = extractScalarValue(tuple);
							} else if (tupleKey.equals("history")) {
								issueData.history = extractIssueHistory(tuple);
							} else {
								throw new IllegalStateException("Not supported: " + tupleKey);
							}
						});
						return issueData;
					}

					private List<ItemData> extractIssueHistory(NodeTuple issueTuple) {
						return ((SequenceNode) issueTuple.getValueNode()).getValue().stream()//
								.map(itemNode -> (MappingNode) itemNode)//
								.map(itemNode -> {
									ItemData itemData = new ItemData();

									itemData.status = extractItemStatus(itemNode);

									itemNode.getValue().forEach(tuple -> {
										String tupleKey = ((ScalarNode) tuple.getKeyNode()).getValue();
										if (tupleKey.equals("dateTime")) {
											itemData.dateTime = extractScalarValue(tuple);
										} else if (tupleKey.equals("source")) {
											itemData.source = extractItemSource(tuple);
										} else {
											throw new IllegalStateException("Not supported: " + tupleKey);
										}
									});
									return itemData;
								}).toList();
					}

					private SourceData extractItemSource(NodeTuple tuple) {
						List<Node> nodes = ((SequenceNode) tuple.getValueNode()).getValue();
						List<Y> list = new LinkedList<>();

						Node rootNode = nodes.get(0);
						list.add(new Y.Root((String) constructScalar((ScalarNode) rootNode)));

						nodes.stream().skip(1).forEach(subnode -> {
							String name = subnode.getTag().getValue().substring(1);
							subnode.setTag(Tag.MAP);
							Object value = getConstructor(subnode).construct(subnode);
							list.add(new Y(name, value));
						});

						return new SourceData(list);
					}

					private String extractItemStatus(MappingNode itemNode) {
						return itemNode.getTag().getValue().substring(1).toUpperCase();
					}

					private String extractScalarValue(NodeTuple tuple) {
						return ((ScalarNode) tuple.getValueNode()).getValue();
					}
				});
			}
		};
		Yaml yamlParser = new Yaml(constructor, representer, dumpOptions);
		DateTimeFormatter dateParser = DateTimeFormatter.ISO_DATE_TIME;

		Function<Issue, byte[]> resourceSerializer = issue -> {
			IssueData data = new IssueData();
			data.title = issue.title();
			data.dateTime = dateParser.format(issue.datetime());
			data.history = issue.history().stream().map(item -> {
				ItemData itemData = new ItemData();
				itemData.dateTime = dateParser.format(item.datetime());
				itemData.status = item.status().name();
				itemData.source = new SourceData(sourceSerializer.apply(item.source()));
				return itemData;
			}).toList();
			return yamlParser.dump(data).getBytes();
		};
		Function<Supplier<byte[]>, Issue> resourceDeserializer = bytes -> {
			String s = new String(bytes.get());
			IssueData data = yamlParser.load(s);
			Issue issue = Issue.create(data.title, ZonedDateTime.from(dateParser.parse(data.dateTime)));
			History history = issue.history();
			for (ItemData itemData : data.history) {
				history.add(new History.Item(ZonedDateTime.from(dateParser.parse(itemData.dateTime)),
						Issue.Status.valueOf(itemData.status), sourceDeserializer.apply(itemData.source.list)));
			}
			return issue;
		};

		String extension = ".issue";
		try {
			createDirectories(repositoryPath);
		} catch (IOException cause) {
			throw new RuntimeException("Cannot create issue repository directory: " + repositoryPath, cause);
		}
		Function<IssueId, Path> pathResolver = (id) -> {
			String datePart = DateTimeFormatter.ISO_LOCAL_DATE.format(id.issue().datetime()).replace('-',
					File.separatorChar);
			Path dayDirectory = repositoryPath.resolve(datePart);
			try {
				createDirectories(dayDirectory);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot create issue directory: " + dayDirectory, cause);
			}

			String timePart = DateTimeFormatter.ISO_LOCAL_TIME.format(id.issue().datetime()).replace(':', '-');
			String addressPart = id.issue().title().replaceAll("[^a-zA-Z0-9]+", "-");
			return dayDirectory.resolve(timePart + "_" + addressPart + extension);
		};
		Supplier<Stream<Path>> pathFinder = () -> {
			try {
				return find(repositoryPath, Integer.MAX_VALUE, (path, attr) -> {
					return path.toString().endsWith(extension) && isRegularFile(path);
				}).sorted(Comparator.<Path>naturalOrder());// TODO Give real-time control over sort
			} catch (IOException cause) {
				throw new RuntimeException("Cannot browse " + repositoryPath, cause);
			}
		};
		return FileRepository.overBytes(//
				identifier, //
				resourceSerializer, resourceDeserializer, //
				pathResolver, pathFinder//
		);
	}

	private static void updateMailsExceptRemovals(Repository<Mail, MailId> mboxRepository,
			Repository<Mail, MailId> mailRepository) {
		RepositoryDiff.of(mailRepository, mboxRepository)//
				.stream()//
				.peek(diff -> {
					Values<Mail, MailId> values = diff.values();
					if (diff.is(Action.ADDITION)) {
						LOGGER.accept("Add \"" + values.newResource().subject() + "\" " + values.newKey());
					} else if (diff.is(Action.REMOVAL)) {
						LOGGER.accept("[pass] Remove \"" + values.oldResource().subject() + "\" " + values.oldKey());
					} else if (diff.is(Action.RESOURCE_REPLACEMENT)) {
						LOGGER.accept("Replace \"" + values.oldResource().subject() + "\" by \""
								+ values.newResource().subject() + "\" at " + values.oldKey());
					} else if (diff.is(Action.KEY_REPLACEMENT)) {
						LOGGER.accept("Reidentify \"" + values.oldResource().subject() + "\" from " + values.oldKey()
								+ " to " + values.newKey());
					} else {
						throw new RuntimeException("Not supported: " + diff);
					}
				})//
				.filter(diff -> !diff.is(Action.REMOVAL))//
				.forEach(diff -> diff.applyTo(mailRepository));
	}

	private static Repository<Mail, MailId> loadMBox(Path mboxPath, Path confMailCleaningPath) {
		MBoxParser parser = new MBoxParser(LOGGER);
		MailCleaningConfiguration confMailCleaning = MailCleaningConfiguration.parser().apply(confMailCleaningPath);
		Repository<Mail, MailId> mboxRepository = new MemoryRepository<>(MailId::fromMail, new LinkedHashMap<>());
		parser.parseMBox(mboxPath)//
				.filter(on(confMailCleaning))//
				// .limit(40)// TODO Remove
				.peek(displayMailOn(LOGGER))//
				// .sorted(comparing(Mail::receivedDate))//
				.forEach(mboxRepository::add);
		return mboxRepository;
	}

	record IssueId(Issue issue) {
	}

	record MailId(ZonedDateTime datetime, String sender) {
		public static MailId fromMail(Mail mail) {
			return new MailId(mail.receivedDate(), mail.sender().email());
		}
	}

	private static FileRepository<Mail, MailId> createMailRepository(Path repositoryPath) {
		Function<Mail, MailId> identifier = MailId::fromMail;

		MBoxParser repositoryParser = new MBoxParser(LOGGER);
		Function<Mail, byte[]> resourceSerializer = (mail) -> {
			return mail.lines().stream().collect(joining("\n")).getBytes();
		};
		Function<Supplier<byte[]>, Mail> resourceDeserializer = (bytesSupplier) -> {
			return new SoftReferencedMail(() -> {
				byte[] bytes = bytesSupplier.get();
				// Use split with negative limit to retain empty strings
				List<String> lines = Arrays.asList(new String(bytes).split("\n", -1));
				return repositoryParser.parseMail(lines);
			});
		};

		String extension = ".mail";
		try {
			createDirectories(repositoryPath);
		} catch (IOException cause) {
			throw new RuntimeException("Cannot create mail repository directory: " + repositoryPath, cause);
		}
		Function<MailId, Path> pathResolver = (id) -> {
			String datePart = DateTimeFormatter.ISO_LOCAL_DATE.format(id.datetime).replace('-', File.separatorChar);
			Path dayDirectory = repositoryPath.resolve(datePart);
			try {
				createDirectories(dayDirectory);
			} catch (IOException cause) {
				throw new RuntimeException("Cannot create mail directory: " + dayDirectory, cause);
			}

			String timePart = DateTimeFormatter.ISO_LOCAL_TIME.format(id.datetime).replace(':', '-');
			String addressPart = id.sender.replaceAll("[^a-zA-Z0-9]+", "-");
			return dayDirectory.resolve(timePart + "_" + addressPart + extension);
		};
		Supplier<Stream<Path>> pathFinder = () -> {
			try {
				return find(repositoryPath, Integer.MAX_VALUE, (path, attr) -> {
					return path.toString().endsWith(extension) && isRegularFile(path);
				}).sorted(Comparator.<Path>naturalOrder());// TODO Give real-time control over sort
			} catch (IOException cause) {
				throw new RuntimeException("Cannot browse " + repositoryPath, cause);
			}
		};

		return FileRepository.overBytes(//
				identifier, //
				resourceSerializer, resourceDeserializer, //
				pathResolver, pathFinder);
	}

	private static Mail.Body.Textual reduceToPlainOrHtmlBody(Mail mail) {
		return (Mail.Body.Textual) Stream.of(mail.body())//
				.flatMap(Main::flattenRecursively)//
				.filter(body -> {
					return body.mimeType().equals(MimeType.Text.PLAIN) //
							|| body.mimeType().equals(MimeType.Text.HTML);
				}).findFirst().orElseThrow();
	}

	private static Stream<? extends Body> flattenRecursively(Body body) {
		return body instanceof Mail.Body.Composed composed //
				? composed.bodies().stream().flatMap(Main::flattenRecursively) //
				: Stream.of(body);
	}

	private static Consumer<Mail> displayMailOn(Consumer<Object> logger) {
		int[] count = { 0 };
		return mail -> {
			logger.accept(count[0] + "> " + mail.lines().get(0) + " about: " + mail.subject());
			count[0]++;
		};
	}

	private static Predicate<Mail> on(MailCleaningConfiguration confMailCleaning) {
		Predicate<Mail> exclusionPredicate = confMailCleaning.getExclude().stream()//
				.map(exclusion -> {
					String header = exclusion.getHeader();
					String content = exclusion.getContains();
					return (Predicate<Mail>) mail -> {
						return mail.headers()//
								.tryGet(header).map(Header::body)//
								.map(body -> body.contains(content))//
								.orElse(false);
					};
				})//
				.reduce(Predicate::or)//
				.orElse(mail -> false);

		return exclusionPredicate.negate();
	}
}
