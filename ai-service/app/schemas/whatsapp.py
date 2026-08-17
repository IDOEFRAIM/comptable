from pydantic import BaseModel, ConfigDict, Field


class WhatsAppImage(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    mime_type: str = Field(alias="mime_type")
    caption: str | None = None


class WhatsAppMessage(BaseModel):
    from_: str = Field(alias="from")
    id: str
    timestamp: str
    type: str
    image: WhatsAppImage | None = None

    model_config = ConfigDict(populate_by_name=True)


class WhatsAppValue(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    messaging_product: str = Field(alias="messaging_product")
    messages: list[WhatsAppMessage] = Field(default_factory=list)


class WhatsAppChange(BaseModel):
    value: WhatsAppValue
    field: str


class WhatsAppEntry(BaseModel):
    id: str
    changes: list[WhatsAppChange]


class WhatsAppWebhookPayload(BaseModel):
    """Miroir de agent.whatsapp.dto.WhatsAppWebhookPayload (records Java imbriques)."""

    object: str
    entry: list[WhatsAppEntry] = Field(default_factory=list)


class SendTemplateRequest(BaseModel):
    """Correspond a WhatsAppCloudApiClient.envoyerTemplate (utilise par le CRM de relance).
    Alias camelCase : cet endpoint est appele par Spring Boot (Jackson, convention camelCase)."""

    model_config = ConfigDict(populate_by_name=True)

    numero_destinataire: str = Field(alias="numeroDestinataire")  # E.164 sans "+", deja normalise
    nom_template: str = Field(alias="nomTemplate")
    langue: str = "fr"
    parametres_corps: list[str] = Field(default_factory=list, alias="parametresCorps")


class SendTemplateResponse(BaseModel):
    wamid: str
